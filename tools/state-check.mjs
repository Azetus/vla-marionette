#!/usr/bin/env node
/**
 * State pacing check.
 *
 * Sends a sequence of four-tick ActionChunks (alternating W held and empty) and
 * verifies that, in tick mode, every expected action tick has a State with a
 * pose. In wall_clock mode it reports the observed State rate instead of
 * requiring exact slot ends.
 *
 * Usage:
 *   node tools/state-check.mjs --port 25580 --out runs/state-check \
 *     [--chunks 20] [--frames 4] [--expect-mode tick] [--tick-interval 1] \
 *     [--expect-hz 10] [--shutdown]
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
const outDir = path.resolve(arg("out", "runs/state-check"));
const chunks = Number(arg("chunks", "20"));
const frames = Number(arg("frames", "4"));
const expectMode = arg("expect-mode", "tick");
const tickInterval = Number(arg("tick-interval", "1"));
const expectHz = arg("expect-hz", "");
const shutdown = process.argv.includes("--shutdown");

if (!Number.isInteger(chunks) || chunks < 1) {
  throw new Error(`chunks must be an integer >= 1, got ${chunks}`);
}
if (!Number.isInteger(frames) || frames < 1 || frames > 20) {
  throw new Error(`frames must be an integer in 1..20, got ${frames}`);
}
if (!["tick", "wall_clock"].includes(expectMode)) {
  throw new Error(`expect-mode must be tick or wall_clock, got ${expectMode}`);
}
if (!Number.isInteger(tickInterval) || tickInterval < 1) {
  throw new Error(`tick-interval must be an integer >= 1, got ${tickInterval}`);
}

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let finished = false;
let failedReason = null;
let shutdownTimer;

const receipts = [];
const statesByTick = new Map();
const stateArrivals = [];
const checks = [];
const chunkInfo = [];

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
      buffer = buffer.subarray(total);
      handleMessage(header);
      if (finished) return;
    }
  });
  socket.on("error", (error) => finish(error));
  socket.on("close", () => {
    if (!finished) finish(new Error("connection closed before the check completed"));
  });
});

function handleMessage(header) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      break;
    case "State":
      lastState = header;
      statesByTick.set(header.tick, header);
      stateArrivals.push({ tick: header.tick, at: Date.now() });
      break;
    case "ActionReceipt":
      receipts.push(header);
      break;
    case "ShutdownAck":
      console.log(`[state] ShutdownAck save=${header.save} reason=${header.reason}`);
      setTimeout(closeAndExit, 200);
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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, label, waitMs = 20000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(25);
  }
  throw new Error(`timeout waiting for ${label}`);
}

function check(condition, label) {
  if (!condition) throw new Error(label);
  checks.push(label);
}

function hasPose(state) {
  return state !== undefined && state !== null &&
    typeof state.x === "number" && typeof state.y === "number" &&
    typeof state.z === "number";
}

async function run() {
  await waitFor(() => session, "SessionInfo");
  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.input_locked === true && lastState.tick > 10 ? lastState : null),
    "stable in-world state");

  check(Number.isInteger(stable.tick) && stable.tick > 0, "stable state tick");

  let lastEndedTick = 0;
  for (let i = 0; i < chunks; i++) {
    const seq = i + 1;
    const startTick = i === 0
      ? stable.tick + 10
      : Math.max(lastState?.tick ?? 0, lastEndedTick) + 5;
    const held = i % 2 === 0;
    const chunkFrames = Array.from({ length: frames },
      () => (held ? { keys: [87] } : {}));
    sendFrame({ type: "ActionChunk", seq, start_tick: startTick, frames: chunkFrames });

    await waitFor(() => receipts.some((item) => item.seq === seq && item.status === "applied"),
      `applied seq=${seq}`, 20000);
    const ended = await waitFor(
      () => receipts.find((item) => item.seq === seq && item.status === "ended"),
      `ended seq=${seq}`, 20000);
    lastEndedTick = ended.tick;
    chunkInfo.push({
      seq,
      held,
      startTick,
      endTick: ended.tick - 1,
      endedTick: ended.tick,
    });
  }

  // Give wall_clock reports a moment to arrive after the last action.
  await sleep(400);

  const expectedTicks = [];
  const missingEnds = [];
  const missingExpectedEnds = [];
  for (const info of chunkInfo) {
    for (let tick = info.startTick; tick <= info.endTick; tick++) {
      if (tick % tickInterval === 0) expectedTicks.push(tick);
    }
    const endState = statesByTick.get(info.endTick);
    if (!hasPose(endState)) {
      missingEnds.push(info.endTick);
    }
    if (info.endTick % tickInterval === 0 && !hasPose(endState)) {
      missingExpectedEnds.push(info.endTick);
    }
  }
  const missingTicks = expectedTicks.filter((tick) =>
    !hasPose(statesByTick.get(tick)));
  const endCoverage = (chunkInfo.length - missingEnds.length) / chunkInfo.length;
  const tickCoverage = expectedTicks.length === 0
    ? 1
    : (expectedTicks.length - missingTicks.length) / expectedTicks.length;
  const observedHz = stateArrivals.length > 1
    ? (stateArrivals.length - 1) * 1000 /
      Math.max(1, stateArrivals[stateArrivals.length - 1].at - stateArrivals[0].at)
    : 0;

  if (expectMode === "tick") {
    check(missingExpectedEnds.length === 0,
      `expected slot-end States: ${missingExpectedEnds.length} missing`);
    check(missingTicks.length === 0,
      `expected action-tick States: ${missingTicks.length} missing`);
  } else {
    check(stateArrivals.length > 0, "wall_clock States received");
    if (expectHz) {
      const expected = Number(expectHz);
      const tolerance = Math.max(1.0, expected * 0.3);
      check(Math.abs(observedHz - expected) <= tolerance,
        `wall_clock rate ${observedHz.toFixed(2)} Hz near ${expected} Hz`);
    }
  }

  return {
    session,
    expectMode,
    chunks,
    frames,
    tickInterval,
    chunkInfo,
    states: stateArrivals.length,
    observedHz: Math.round(observedHz * 100) / 100,
    endCoverage: Math.round(endCoverage * 1000) / 1000,
    tickCoverage: Math.round(tickCoverage * 1000) / 1000,
    missingEnds,
    missingTicks,
    checks,
  };
}

function finish(error, result) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  if (error) {
    failedReason = error.message;
    console.error(`[state] fail: ${failedReason}`);
  }
  const report = {
    ...(result ?? {}),
    receipts,
    failedReason,
    shutdownRequested: shutdown,
    passed: !failedReason,
  };
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[state] chunks=${chunkInfo.length} states=${stateArrivals.length} `
    + `endCoverage=${report.endCoverage ?? "-"} tickCoverage=${report.tickCoverage ?? "-"} `
    + `passed=${!failedReason}`);
  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "state-check" });
    shutdownTimer = setTimeout(closeAndExit, 10000);
  } else {
    closeAndExit();
  }
}

function closeAndExit() {
  clearTimeout(timer);
  clearTimeout(shutdownTimer);
  if (socketRef) socketRef.destroy();
  const passed = readPassed();
  server.close(() => process.exit(passed ? 0 : 2));
}

function readPassed() {
  try {
    return JSON.parse(fs.readFileSync(path.join(outDir, "report.json"), "utf8")).passed === true;
  } catch {
    return false;
  }
}

const timer = setTimeout(() => {
  finish(new Error(`timeout after ${timeoutMs} ms; state=${lastState?.tick ?? "-"}`));
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[state] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run().then((result) => finish(null, result)).catch((error) => finish(error));
