#!/usr/bin/env node
/**
 * Continuous-mining check.
 *
 * The host places a target block in front of the player, confirms it with
 * QueryBlock, holds raw left mouse button frames through ActionChunk, and reads
 * the target again. Foreground and minimized runs use the same script; the
 * caller minimizes the window when testing the background case.
 *
 * Usage:
 *   node tools/dig-check.mjs --port 25580 --out runs/dig-check [--shutdown]
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
const timeoutMs = Number(arg("timeout", "120000"));
const outDir = path.resolve(arg("out", "runs/dig-check"));
const block = arg("block", "minecraft:dirt");
const target = parseTriplet(arg("target", "198,-59,200"), [198, -59, 200]);
const stand = parseTriplet(arg("stand", "200.5,-60,200.5"), [200.5, -60, 200.5]);
const yaw = Number(arg("yaw", "90"));
const pitch = Number(arg("pitch", "0"));
const maxChunks = Number(arg("chunks", "2"));
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
const chatAcks = new Map();
const pendingChat = new Map();
const pendingQueries = new Map();
const checks = [];
const states = [];
const results = [];
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
      const id = header.request_id;
      if (id !== null && pendingChat.has(id)) {
        const resolve = pendingChat.get(id);
        pendingChat.delete(id);
        resolve(header);
      }
      break;
    }
    case "QueryBlockResult": {
      const id = header.request_id;
      if (id !== null && pendingQueries.has(id)) {
        const resolve = pendingQueries.get(id);
        pendingQueries.delete(id);
        resolve(header);
      }
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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, label, waitMs = 20000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(50);
  }
  throw new Error(`timeout waiting for ${label}`);
}

function check(condition, label) {
  if (!condition) throw new Error(label);
  checks.push(label);
}

async function waitReceipts(startTick, chunkSeq) {
  const ended = await waitFor(
    () => receipts.find((receipt) => receipt.seq === chunkSeq &&
      receipt.status === "ended" && receipt.tick === startTick + framesPerChunk),
    "ended receipt", 15000);
  check(receipts.some((receipt) => receipt.seq === chunkSeq &&
    receipt.status === "received"), "received receipt");
  check(receipts.some((receipt) => receipt.seq === chunkSeq &&
    receipt.status === "applied" && receipt.tick === startTick), "applied receipt");
  check(!receipts.some((receipt) => receipt.status === "rejected"),
    "no rejected receipt");
  return ended;
}

async function run() {
  await waitFor(() => session, "SessionInfo", 30000);
  check(session.image?.width === 854 && session.image?.height === 480,
    "image spec 854x480");
  check((session.capabilities ?? []).includes("raw_input"), "raw_input capability");
  check((session.capabilities ?? []).includes("query_block"), "query_block capability");
  check((session.capabilities ?? []).includes("chat"), "chat capability");

  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.on_ground === true && lastState.capture?.valid === true &&
      lastState.tick > 10 ? lastState : null),
    "stable in-world state", 30000);
  check(stable.input_locked === true, "input lock armed");

  let ack = await chat("/gamemode survival");
  check(ack.ok === true && ack.kind === "command", "gamemode survival accepted");
  await sleep(300);
  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} air`);
  check(ack.ok === true && ack.kind === "command", "clear target accepted");
  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} ${block}`);
  check(ack.ok === true && ack.kind === "command", "place target accepted");
  ack = await chat(`/tp @s ${stand[0]} ${stand[1]} ${stand[2]} ${yaw} ${pitch}`);
  check(ack.ok === true && ack.kind === "command", "teleport accepted");

  const before = await waitFor(() => {
    const state = lastState;
    return state && Math.abs(state.x - stand[0]) < 0.25 &&
      Math.abs(state.z - stand[2]) < 0.25 && Math.abs(state.yaw - yaw) < 1 &&
      Math.abs(state.pitch - pitch) < 1 && state.on_ground === true &&
      state.screen_open === false ? state : null;
  }, "teleport state", 20000);
  await sleep(400);

  let targetBefore = await queryBlock(target);
  check(targetBefore.ok === true && targetBefore.block_state === block,
    `target before mining is ${block}: ${JSON.stringify(targetBefore)}`);
  let crosshair = await queryBlock();
  check(crosshair.ok === true && crosshair.query === "crosshair",
    "crosshair query ok");
  check(Array.isArray(crosshair.pos) && crosshair.pos[0] === target[0] &&
    crosshair.pos[1] === target[1] && crosshair.pos[2] === target[2],
    `crosshair sees target: ${JSON.stringify(crosshair.pos)}`);
  check(crosshair.face === "east", `crosshair face east: ${crosshair.face}`);

  let targetAfter = null;
  let heldDuringAction = null;
  let attempts = 0;
  const result = {
    session,
    target,
    stand,
    block,
    before: compactState(before),
    targetBefore,
    crosshair,
    attempts,
    results,
    targetAfter,
    heldDuringAction: null,
    receipts,
  };
  currentResult = result;

  for (let attempt = 0; attempt < maxChunks; attempt++) {
    attempts++;
    const startTick = lastState.tick + 6;
    const chunkSeq = seq++;
    sendFrame({
      type: "ActionChunk",
      seq: chunkSeq,
      start_tick: startTick,
      frames: Array.from({ length: framesPerChunk }, () => ({ buttons: [0] })),
    });
    console.log(`[dig] attempt ${attempts}: left button ${framesPerChunk} ticks from ${startTick}`);
    const ended = await waitReceipts(startTick, chunkSeq);
    heldDuringAction = heldDuringAction ?? states.find((item) =>
      item.header.tick >= startTick && item.header.tick < ended.tick &&
      (item.header.held?.buttons ?? []).includes(0))?.header ?? null;
    await sleep(400);
    targetAfter = await queryBlock(target);
    results.push({
      attempt: attempts,
      startTick,
      endedTick: ended.tick,
      target: targetAfter,
    });
    result.attempts = attempts;
    result.targetAfter = targetAfter;
    result.heldDuringAction = heldDuringAction ? compactState(heldDuringAction) : null;
    if (targetAfter.ok === true && targetAfter.block_state === "minecraft:air") {
      break;
    }
  }

  check(targetAfter?.ok === true && targetAfter.block_state === "minecraft:air",
    `target after mining is air: ${JSON.stringify(targetAfter)}`);
  check(heldDuringAction !== null, "held buttons contained 0 during mining");

  const afterFramePath = path.join(outDir, "after.jpg");
  if (lastObservation) {
    fs.writeFileSync(afterFramePath, lastObservation.body);
  }

  result.afterFrame = lastObservation ? afterFramePath : null;
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

