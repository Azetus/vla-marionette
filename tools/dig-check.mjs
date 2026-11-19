#!/usr/bin/env node
/**
 * Continuous-mining check for one or more hand-mined blocks.
 *
 * For each block the host clears the player's inventory (empty main hand),
 * places the block in front of the player, confirms it with QueryBlock, holds
 * raw left-button frames through chained ActionChunks, and reads the target
 * again. Chunks are scheduled back-to-back so the held button never gets a
 * release tick; stone needs about 150 ticks by hand.
 *
 * Usage:
 *   node tools/dig-check.mjs --port 25580 --out runs/dig-check [--shutdown]
 *   node tools/dig-check.mjs --blocks minecraft:dirt,minecraft:stone --chunks 1,8
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

function parseTriplet(value, fallback) {
  if (!value) return fallback;
  const parts = value.split(",").map(Number);
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) {
    throw new Error(`invalid coordinate triplet: ${value}`);
  }
  return parts;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "180000"));
const outDir = path.resolve(arg("out", "runs/dig-check"));
const blocks = arg("blocks", "minecraft:dirt,minecraft:stone")
  .split(",").map((item) => item.trim()).filter(Boolean);
let chunkCounts = arg("chunks", "1,8").split(",").map(Number);
if (chunkCounts.length === 1 && blocks.length > 1) {
  chunkCounts = blocks.map(() => chunkCounts[0]);
}
if (blocks.length === 0 || blocks.length !== chunkCounts.length ||
  chunkCounts.some((count) => !Number.isInteger(count) || count < 1)) {
  throw new Error("blocks and chunks must have the same non-empty length");
}
const target = parseTriplet(arg("target", "198,-59,200"), [198, -59, 200]);
const stand = parseTriplet(arg("stand", "200.5,-60,200.5"), [200.5, -60, 200.5]);
const yaw = Number(arg("yaw", "90"));
const pitch = Number(arg("pitch", "0"));
const framesPerChunk = Number(arg("frames", "20"));
const shutdown = process.argv.includes("--shutdown");

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let lastObservation = null;
let finished = false;
let failedReason = null;
let shutdownAck = null;
let requestCounter = 100;
let seq = 1;
let overallTimer;

const receipts = [];
const pendingChat = new Map();
const pendingQueries = new Map();
const pendingInventories = new Map();
const checks = [];
const states = [];
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
      finish(new Error("connection closed before the dig check completed"));
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
      states.push({ header, recv: Date.now() });
      break;
    case "Observation":
      lastObservation = { header, body };
      break;
    case "ActionReceipt":
      receipts.push(header);
      break;
    case "ChatAck": {
      resolvePending(pendingChat, header);
      break;
    }
    case "QueryBlockResult": {
      resolvePending(pendingQueries, header);
      break;
    }
    case "QueryInventoryResult": {
      resolvePending(pendingInventories, header);
      break;
    }
    case "ShutdownAck":
      shutdownAck = header;
      console.log(`[dig] ShutdownAck save=${header.save} reason=${header.reason}`);
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

function queryBlock(pos) {
  const id = nextId();
  const header = { type: "QueryBlock", request_id: id };
  if (pos) header.pos = pos;
  return sendRequest(header, pendingQueries);
}

function queryInventory() {
  const id = nextId();
  return sendRequest({ type: "QueryInventory", request_id: id },
    pendingInventories);
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
    (tick === undefined || receipt.tick === tick)), label, 20000);
}

async function runBlock(block, chunks) {
  let ack = await chat("/clear @s");
  check(ack.ok === true && ack.kind === "command", `${block}: clear inventory accepted`);
  await sleep(300);
  const inventory = await queryInventory();
  check(inventory.ok === true, `${block}: inventory query ok`);
  const selectedEmpty = !inventory.slots.some((slot) =>
    slot.slot === inventory.selected && slot.count > 0);
  check(selectedEmpty, `${block}: main hand empty`);

  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} air`);
  check(ack.ok === true, `${block}: clear target accepted`);
  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} ${block}`);
  check(ack.ok === true, `${block}: place target accepted`);
  ack = await chat(`/tp @s ${stand[0]} ${stand[1]} ${stand[2]} ${yaw} ${pitch}`);
  check(ack.ok === true, `${block}: teleport accepted`);
  const before = await waitFor(() => {
    const state = lastState;
    return state && Math.abs(state.x - stand[0]) < 0.25 &&
      Math.abs(state.z - stand[2]) < 0.25 && Math.abs(state.yaw - yaw) < 1 &&
      Math.abs(state.pitch - pitch) < 1 && state.on_ground === true &&
      state.screen_open === false ? state : null;
  }, `${block}: teleport state`);
  await sleep(400);

  const targetBefore = await queryBlock(target);
  check(targetBefore.ok === true && targetBefore.block_state === block,
    `${block}: target before mining (${JSON.stringify(targetBefore)})`);
  const crosshair = await queryBlock();
  check(crosshair.ok === true && crosshair.query === "crosshair",
    `${block}: crosshair query ok`);
  check(Array.isArray(crosshair.pos) && crosshair.pos[0] === target[0] &&
    crosshair.pos[1] === target[1] && crosshair.pos[2] === target[2],
    `${block}: crosshair sees target (${JSON.stringify(crosshair.pos)})`);
  check(crosshair.face === "east", `${block}: crosshair face east`);

  const firstStart = lastState.tick + 6;
  const chunkInfo = [];
  for (let index = 0; index < chunks; index++) {
    const startTick = firstStart + index * framesPerChunk;
    const chunkSeq = seq++;
    sendFrame({
      type: "ActionChunk",
      seq: chunkSeq,
      start_tick: startTick,
      frames: Array.from({ length: framesPerChunk }, () => ({ buttons: [0] })),
    });
    const applied = await waitReceipt(chunkSeq, "applied", startTick,
      `${block}: applied chunk ${index + 1}/${chunks}`);
    chunkInfo.push({ seq: chunkSeq, startTick, applied: applied.tick });
    console.log(`[dig] ${block}: chunk ${index + 1}/${chunks} applied at ${applied.tick}`);
  }

  const last = chunkInfo[chunkInfo.length - 1];
  const ended = await waitReceipt(last.seq, "ended", last.startTick + framesPerChunk,
    `${block}: ended last chunk`);
  await sleep(500);
  const targetAfter = await queryBlock(target);
  check(targetAfter.ok === true && targetAfter.block_state === "minecraft:air",
    `${block}: target after mining is air (${JSON.stringify(targetAfter)})`);

  for (const chunk of chunkInfo) {
    check(receipts.some((receipt) => receipt.seq === chunk.seq &&
      receipt.status === "received"), `${block}: chunk ${chunk.seq} received`);
    check(receipts.some((receipt) => receipt.seq === chunk.seq &&
      receipt.status === "applied" && receipt.tick === chunk.startTick),
      `${block}: chunk ${chunk.seq} applied at start`);
    check(receipts.some((receipt) => receipt.seq === chunk.seq &&
      receipt.status === "ended" &&
      receipt.tick === chunk.startTick + framesPerChunk),
      `${block}: chunk ${chunk.seq} ended`);
  }
  check(!receipts.some((receipt) => receipt.status === "rejected"),
    `${block}: no rejected receipt`);

  const heldDuringAction = states.find((item) =>
    item.header.tick >= firstStart &&
    item.header.tick < last.startTick + framesPerChunk &&
    (item.header.held?.buttons ?? []).includes(0))?.header ?? null;
  check(heldDuringAction !== null, `${block}: held buttons contained 0`);
  check(before.input_locked === true, `${block}: input lock armed`);

  return {
    block,
    chunks,
    firstStart,
    lastEnd: ended.tick,
    before: compactState(before),
    targetBefore,
    crosshair,
    targetAfter,
    heldDuringAction: compactState(heldDuringAction),
    selectedEmpty,
    chunkInfo,
    receiptCount: receipts.filter((receipt) =>
      chunkInfo.some((chunk) => chunk.seq === receipt.seq)).length,
  };
}

async function run() {
  await waitFor(() => session, "SessionInfo", 30000);
  check(session.image?.width === 854 && session.image?.height === 480,
    "image spec 854x480");
  for (const capability of ["raw_input", "query_block", "query_inventory", "chat"]) {
    check((session.capabilities ?? []).includes(capability),
      `capability ${capability}`);
  }

  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.on_ground === true && lastState.capture?.valid === true &&
      lastState.tick > 10 ? lastState : null),
    "stable in-world state", 30000);
  check(stable.input_locked === true, "input lock armed");

  let ack = await chat("/gamemode survival");
  check(ack.ok === true && ack.kind === "command", "gamemode survival accepted");
  await sleep(300);

  const result = { session, blocks: [] };
  currentResult = result;
  for (let index = 0; index < blocks.length; index++) {
    const blockResult = await runBlock(blocks[index], chunkCounts[index]);
    result.blocks.push(blockResult);
    console.log(`[dig] ${blocks[index]} passed`);
  }

  if (lastObservation) {
    const framePath = path.join(outDir, "after.jpg");
    fs.writeFileSync(framePath, lastObservation.body);
    result.afterFrame = framePath;
  }
  return result;
}

function compactState(state) {
  if (!state) return null;
  return {
    tick: state.tick,
    x: state.x,
    y: state.y,
    z: state.z,
    yaw: state.yaw,
    pitch: state.pitch,
    on_ground: state.on_ground,
    screen_open: state.screen_open,
    input_locked: state.input_locked,
    held: state.held,
    capture: state.capture,
  };
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
    console.error(`[dig] fail: ${failedReason}`);
  }
  const report = buildReport(currentResult);
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[dig] checks=${checks.length} passed=${!failedReason}`);

  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "dig-check" });
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
  console.log(`[dig] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run()
  .then(() => finish(null))
  .catch((error) => finish(error));
