#!/usr/bin/env node
/**
 * Modifier-bridge check.
 *
 * Verifies that injected Ctrl is visible to vanilla's physical-key polling:
 * Ctrl+Q must drop the whole selected stack, while Q alone drops one item.
 * This is the acceptance for MinecraftModifierMixin; no protocol change.
 *
 * Usage:
 *   node tools/modifier-check.mjs --port 25580 --out runs/modifier-check [--shutdown]
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "120000"));
const outDir = path.resolve(arg("out", "runs/modifier-check"));
const shutdown = process.argv.includes("--shutdown");

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let finished = false;
let failedReason = null;
let shutdownAck = null;
let requestCounter = 100;
let seq = 1;
let overallTimer;

const receipts = [];
const pendingChat = new Map();
const pendingInventories = new Map();
const checks = [];
let currentResult = null;

const server = net.createServer((socket) => {
  if (socketRef && !socketRef.destroyed) {
    socket.destroy();
    return;
  }
  socketRef = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      if (headerLen > frameLen - 4) {
        finish(new Error("invalid frame lengths"));
        return;
      }
      const total = 4 + frameLen;
      if (buffer.length < total) return;
      const header = JSON.parse(buffer.subarray(8, 8 + headerLen).toString("utf8"));
      const body = Buffer.from(buffer.subarray(8 + headerLen, total));
      buffer = buffer.subarray(total);
      handleMessage(header, body);
      if (finished) return;
    }
  });
  socket.on("error", (error) => {
    if (!finished) finish(error);
  });
  socket.on("close", () => {
    if (!finished && !shutdownAck) {
      finish(new Error("connection closed before the modifier check completed"));
    }
  });
});

function handleMessage(header, body) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      break;
    case "State":
      lastState = header;
      break;
    case "ActionReceipt":
      receipts.push(header);
      break;
    case "ChatAck":
      resolvePending(pendingChat, header);
      break;
    case "QueryInventoryResult":
      resolvePending(pendingInventories, header);
      break;
    case "ShutdownAck":
      shutdownAck = header;
      console.log(`[mod] ShutdownAck save=${header.save} reason=${header.reason}`);
      break;
    default:
      break;
  }
}

function resolvePending(pending, header) {
  const id = header.request_id;
  if (id !== null && pending.has(id)) {
    const resolve = pending.get(id);
    pending.delete(id);
    resolve(header);
  }
}

function sendFrame(header) {
  const json = Buffer.from(JSON.stringify(header), "utf8");
  const frame = Buffer.alloc(8 + json.length);
  frame.writeUInt32BE(4 + json.length, 0);
  frame.writeUInt32BE(json.length, 4);
  json.copy(frame, 8);
  socketRef.write(frame);
}

function sendRequest(header, pending, waitMs = 10000) {
  const id = header.request_id;
  return new Promise((resolve, reject) => {
    pending.set(id, resolve);
    sendFrame(header);
    setTimeout(() => {
      if (pending.delete(id)) {
        reject(new Error(`timeout waiting for response to request ${id}`));
      }
    }, waitMs);
  });
}

function nextId() {
  return requestCounter++;
}

function chat(text) {
  const id = nextId();
  return sendRequest({ type: "Chat", request_id: id, text }, pendingChat);
}

function queryInventory() {
  const id = nextId();
  return sendRequest({ type: "QueryInventory", request_id: id }, pendingInventories);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, label, waitMs = 20000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(30);
  }
  throw new Error(`timeout waiting for ${label}`);
}

function check(condition, label) {
  if (!condition) throw new Error(label);
  checks.push(label);
}

function waitReceipt(chunkSeq, status, tick, label) {
  return waitFor(() => receipts.find((receipt) =>
    receipt.seq === chunkSeq && receipt.status === status &&
    (tick === undefined || receipt.tick === tick)), label, 15000);
}

async function pressKeys(keys) {
  const startTick = lastState.tick + 4;
  const chunkSeq = seq++;
  sendFrame({
    type: "ActionChunk",
    seq: chunkSeq,
    start_tick: startTick,
    frames: [{ keys }],
  });
  const applied = await waitReceipt(chunkSeq, "applied", startTick,
    `applied keys ${JSON.stringify(keys)}`);
  const ended = await waitReceipt(chunkSeq, "ended", startTick + 1,
    `ended keys ${JSON.stringify(keys)}`);
  check(receipts.some((receipt) => receipt.seq === chunkSeq &&
    receipt.status === "received"), `received keys ${JSON.stringify(keys)}`);
  check(!receipts.some((receipt) => receipt.status === "rejected"),
    "no rejected receipt");
  return { startTick, applied: applied.tick, ended: ended.tick };
}

async function selectedStack() {
  const inventory = await queryInventory();
  check(inventory.ok === true, "inventory query ok");
  const slot = inventory.slots.find((entry) => entry.item === "minecraft:dirt");
  return { inventory, slot };
}

async function prepareStack() {
  let ack = await chat("/clear @s");
  check(ack.ok === true, "clear inventory accepted");
  ack = await chat("/give @s minecraft:dirt 5");
  check(ack.ok === true, "give dirt accepted");
  await sleep(400);

  let stack = await selectedStack();
  check(Boolean(stack.slot), "dirt stack present");
  check(stack.slot.count === 5, `dirt count is 5 (got ${stack.slot?.count})`);
  if (stack.slot.slot > 8) {
    throw new Error(`dirt is not in the hotbar: slot ${stack.slot.slot}`);
  }
  if (stack.inventory.selected !== stack.slot.slot) {
    await pressKeys([49 + stack.slot.slot]);
    await waitFor(() => lastState?.hotbar === stack.slot.slot,
      "hotbar selection", 5000);
    stack = await selectedStack();
  }
  return stack;
}

async function run() {
  await waitFor(() => session, "SessionInfo", 30000);
  check((session.image?.width ?? 0) > 0 && (session.image?.height ?? 0) > 0,
    "image spec");
  check(session.render?.width === session.image?.width &&
    session.render?.height === session.image?.height &&
    session.window?.width === session.image?.width &&
    session.window?.height === session.image?.height,
    "render/window match image size");
  for (const capability of ["raw_input", "query_inventory", "chat"]) {
    check((session.capabilities ?? []).includes(capability),
      `capability ${capability}`);
  }
  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.on_ground === true && lastState.capture?.valid === true &&
      lastState.tick > 10 && lastState.input_locked === true ? lastState : null),
    "stable in-world state", 30000);
  check(stable.input_locked === true, "input lock armed");

  let ack = await chat("/gamemode survival");
  check(ack.ok === true && ack.kind === "command", "gamemode survival accepted");
  await sleep(300);

  const result = { session, steps: [] };
  currentResult = result;

  // Ctrl+Q must drop the whole stack.
  let stack = await prepareStack();
  const beforeStack = { ...stack.slot };
  const ctrlQ = await pressKeys([341, 81]);
  await sleep(250);
  let after = await selectedStack();
  const afterCtrlQ = after.slot ?? null;
  check(afterCtrlQ === null,
    `Ctrl+Q drops the whole stack (remaining ${afterCtrlQ?.count ?? 0})`);
  result.steps.push({
    name: "ctrl_q",
    keys: [341, 81],
    before: beforeStack,
    after: afterCtrlQ,
    receipts: ctrlQ,
  });

  // Move away from the dropped stack so its pickup delay cannot pollute the
  // single-Q count.
  ack = await chat("/tp @s 300.5 -60 300.5 0 0");
  check(ack.ok === true, "teleport away accepted");
  await waitFor(() => lastState &&
    Math.abs(lastState.x - 300.5) < 0.25 &&
    Math.abs(lastState.z - 300.5) < 0.25, "teleport away state", 15000);
  await sleep(300);

  // Q alone must drop one item.
  stack = await prepareStack();
  const beforeSingle = { ...stack.slot };
  const singleQ = await pressKeys([81]);
  await sleep(250);
  after = await selectedStack();
  const afterSingleQ = after.slot ?? null;
  check(afterSingleQ !== null && afterSingleQ.count === 4,
    `Q drops one item (remaining ${afterSingleQ?.count ?? 0})`);
  result.steps.push({
    name: "q",
    keys: [81],
    before: beforeSingle,
    after: afterSingleQ,
    receipts: singleQ,
  });

  return result;
}

function buildReport(data) {
  return {
    session,
    checks,
    failedReason,
    shutdownRequested: shutdown,
    passed: !failedReason,
    ...(data ?? {}),
    allReceipts: receipts,
  };
}

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(overallTimer);
  if (error) {
    failedReason = error.message;
    console.error(`[mod] fail: ${failedReason}`);
  }
  const report = buildReport(currentResult);
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[mod] checks=${checks.length} passed=${!failedReason}`);

  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "modifier-check" });
    const deadline = Date.now() + 10000;
    const timer = setInterval(() => {
      if (shutdownAck || Date.now() > deadline) {
        clearInterval(timer);
        closeAndExit();
      }
    }, 100);
  } else {
    closeAndExit();
  }
}

function closeAndExit() {
  clearTimeout(overallTimer);
  if (socketRef) socketRef.destroy();
  server.close(() => process.exit(failedReason ? 2 : 0));
}

overallTimer = setTimeout(() => {
  finish(new Error(`timeout after ${timeoutMs} ms; state=${lastState?.tick ?? "-"}`));
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[mod] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run()
  .then((result) => {
    currentResult = result;
    finish(null);
  })
  .catch((error) => finish(error));


