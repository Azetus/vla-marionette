#!/usr/bin/env node
/**
 * Minimized-window capture and forward check.
 *
 * The client must already be minimized before this host starts. Window state is
 * verified by the launcher with IsIconic; the host records 30 s of Observation
 * cadence, then teleports to an empty superflat area and sends one six-tick W
 * chunk while still minimized.
 *
 * Usage:
 *   node tools/background-check.mjs --port 25580 --out runs/background [--shutdown]
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const seconds = Number(arg("seconds", "30"));
const frameCount = Number(arg("frames", "6"));
const timeoutMs = Number(arg("timeout", "120000"));
const outDir = path.resolve(arg("out", "runs/background-check"));
const shutdown = process.argv.includes("--shutdown");

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let measuring = false;
let finished = false;
let failedReason = null;
let shutdownAck = null;
let requestCounter = 100;
let overallTimer;

const measurement = [];
const allObservations = [];
const stateLog = [];
const receipts = [];
const pendingChat = new Map();
const checks = [];

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
      finish(new Error("connection closed before the check completed"));
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
      stateLog.push({ header, recv: Date.now() });
      break;
    case "Observation": {
      const dims = jpegDimensions(body);
      const observation = {
        header,
        dims,
        body,
        recv: Date.now(),
        bodyLen: body.length,
      };
      allObservations.push(observation);
      if (measuring) measurement.push(observation);
      break;
    }
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
    case "ShutdownAck":
      shutdownAck = header;
      console.log(`[bg] ShutdownAck save=${header.save} reason=${header.reason}`);
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

function sendRequest(header, waitMs = 10000) {
  const id = header.request_id;
  return new Promise((resolve, reject) => {
    pendingChat.set(id, resolve);
    sendFrame(header);
    setTimeout(() => {
      if (pendingChat.delete(id)) {
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
  return sendRequest({ type: "Chat", request_id: id, text });
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

async function run() {
  await waitFor(() => session, "SessionInfo", 30000);
  check(session.image?.width === 854 && session.image?.height === 480 &&
    session.image?.pixel_format === "jpeg", "image spec 854x480 jpeg");
  for (const capability of ["observation", "state", "raw_input"]) {
    check((session.capabilities ?? []).includes(capability), `capability ${capability}`);
  }

  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.on_ground === true && lastState.capture?.valid === true &&
      lastState.tick > 10 ? lastState : null),
    "stable in-world state", 30000);
  check(stable.input_locked === true, "input lock armed");

  // 30 s minimized observation.
  console.log(`[bg] measuring ${seconds}s of minimized observations`);
  const measureStart = Date.now();
  measuring = true;
  while (Date.now() - measureStart < seconds * 1000) {
    await sleep(100);
  }
  measuring = false;
  const measureEnd = Date.now();

  const observations = measurement;
  check(observations.length > 1, "not enough observations while minimized");
  const submits = observations.map((item) => item.header.submit_ns);
  const intervals = [];
  for (let i = 1; i < submits.length; i++) {
    intervals.push((submits[i] - submits[i - 1]) / 1e6);
  }
  const sorted = [...intervals].sort((a, b) => a - b);
  const meanInterval = intervals.reduce((sum, value) => sum + value, 0) / intervals.length;
  const spanMs = (submits[submits.length - 1] - submits[0]) / 1e6;
  const fps = spanMs > 0 ? ((observations.length - 1) * 1000) / spanMs : 0;
  const wallFps = (observations.length * 1000) / (measureEnd - measureStart);
  const p50 = percentile(sorted, 0.50);
  const p95 = percentile(sorted, 0.95);
  const maxInterval = sorted[sorted.length - 1];
  const allJpeg = observations.every((item) =>
    item.dims && item.dims.width === 854 && item.dims.height === 480);
  let obsIdGaps = 0;
  for (let i = 1; i < observations.length; i++) {
    const gap = observations[i].header.obs_id - observations[i - 1].header.obs_id - 1;
    if (gap > 0) obsIdGaps += gap;
  }

  const statesDuring = stateLog.filter((item) =>
    item.recv >= measureStart && item.recv <= measureEnd);
  const encodeFailures = statesDuring.reduce((max, item) =>
    Math.max(max, item.header.capture?.encode_failures ?? 0), 0);
  const captureValidAll = statesDuring.length > 0 &&
    statesDuring.every((item) => item.header.capture?.valid === true);
  const screenNullAll = statesDuring.every((item) => item.header.screen_open === false);
  const lockedAll = statesDuring.every((item) => item.header.input_locked === true);

  const savedFrames = saveMeasurementFrames(observations);

  const result = {
    session,
    measurement: {
      seconds,
      observations: observations.length,
      fps: round(fps),
      wallFps: round(wallFps),
      meanIntervalMs: round(meanInterval),
      p50Ms: round(p50),
      p95Ms: round(p95),
      maxIntervalMs: round(maxInterval),
      obsIdGaps,
      encodeFailures,
      captureValidAll,
      screenNullAll,
      lockedAll,
      allJpeg,
      states: statesDuring.length,
    },
    frames: savedFrames,
  };
  currentResult = result;

  check(observations.length >= Math.floor(4.5 * seconds),
    `observations ${observations.length} < ${Math.floor(4.5 * seconds)}`);
  check(fps >= 4.5, `capture fps ${fps.toFixed(2)} < 4.5`);
  check(p95 <= 300, `p95 interval ${p95.toFixed(1)} ms > 300`);
  check(maxInterval <= 500, `max interval ${maxInterval.toFixed(1)} ms > 500`);
  check(allJpeg, "all observations are 854x480 JPEG");
  check(encodeFailures === 0, `encode failures ${encodeFailures}`);
  check(captureValidAll, "capture valid for every state during measurement");
  check(screenNullAll, "no screen opened during measurement");
  check(lockedAll, "input lock stayed armed during measurement");

  // Deterministic forward setup: survival + empty superflat area + yaw 0.
  let ack = await chat("/gamemode survival");
  check(ack.ok === true && ack.kind === "command", "gamemode survival accepted");
  await sleep(400);
  ack = await chat("/tp @s 200.5 -60 200.5 0 0");
  check(ack.ok === true && ack.kind === "command", "teleport accepted");

  const beforeAction = await waitFor(() => {
    const state = lastState;
    return state && Math.abs(state.x - 200.5) < 0.25 &&
      Math.abs(state.z - 200.5) < 0.25 && Math.abs(state.yaw) < 1 &&
      Math.abs(state.pitch) < 1 && state.on_ground === true &&
      state.screen_open === false ? state : null;
  }, "teleport state", 20000);
  await sleep(300);

  const startTick = lastState.tick + 6;
  const actionSentAt = Date.now();
  sendFrame({
    type: "ActionChunk",
    seq: 1,
    start_tick: startTick,
    frames: Array.from({ length: frameCount }, () => ({ keys: [87] })),
  });
  console.log(`[bg] sent forward frames=${frameCount} start=${startTick}`);

  const ended = await waitFor(
    () => receipts.find((receipt) => receipt.status === "ended"),
    "ended receipt", 15000);
  check(receipts.some((receipt) => receipt.status === "received"), "received receipt");
  check(receipts.some((receipt) => receipt.status === "applied" &&
    receipt.tick === startTick), "applied receipt at start tick");
  check(!receipts.some((receipt) => receipt.status === "rejected"),
    `no rejected receipt (${JSON.stringify(receipts.filter((r) => r.status === "rejected"))})`);
  check(ended.tick === startTick + frameCount,
    `ended tick ${ended.tick} != ${startTick + frameCount}`);

  const afterAction = await waitFor(
    () => (lastState && lastState.tick >= ended.tick ? lastState : null),
    "post-action state", 10000);
  const postObservation = await waitFor(
    () => allObservations.find((item) =>
      item.recv >= actionSentAt && item.header.state_tick >= ended.tick),
    "post-action observation", 15000);

  const dx = afterAction.x - beforeAction.x;
  const dz = afterAction.z - beforeAction.z;
  const dy = afterAction.y - beforeAction.y;
  const distance = Math.hypot(dx, dz);
  const dot = distance > 1e-6 ? dz / distance : 0;
  const heldDuringAction = stateLog.find((item) =>
    item.header.tick >= startTick && item.header.tick < ended.tick &&
    (item.header.held?.keys ?? []).includes(87))?.header ?? null;

  check(distance >= 0.15, `forward distance ${distance.toFixed(3)} < 0.15`);
  check(dot >= 0.90, `forward direction dot ${dot.toFixed(3)} < 0.90`);
  check(Math.abs(dy) <= 0.20, `vertical delta ${dy.toFixed(3)} > 0.20`);
  check(afterAction.on_ground === true, "post-action on_ground");
  check(afterAction.screen_open === false, "no screen after action");
  check(postObservation.header.state_tick >= ended.tick,
    "post-action observation is at/after ended");

  const afterFramePath = path.join(outDir, "after.jpg");
  fs.writeFileSync(afterFramePath, postObservation.body);

  result.teleport = {
    before: compactState(beforeAction),
    afterTp: compactState(afterAction),
  };
  result.action = {
    startTick,
    frames: frameCount,
    receipts,
    before: compactState(beforeAction),
    after: compactState(afterAction),
    distance: round(distance),
    directionDot: round(dot),
    dy: round(dy),
    heldDuringAction: heldDuringAction ? compactState(heldDuringAction) : null,
    postObservation: {
      obs_id: postObservation.header.obs_id,
      state_tick: postObservation.header.state_tick,
    },
    afterFrame: afterFramePath,
  };
  return result;
}

function saveMeasurementFrames(observations) {
  const saved = [];
  const indexes = [...new Set([0, Math.floor(observations.length / 2),
    observations.length - 1])];
  for (const index of indexes) {
    const item = observations[index];
    const file = path.join(outDir, `minimized-${index}.jpg`);
    fs.writeFileSync(file, item.body);
    saved.push({
      file,
      obs_id: item.header.obs_id,
      state_tick: item.header.state_tick,
      width: item.dims?.width ?? null,
      height: item.dims?.height ?? null,
      bytes: item.bodyLen,
    });
  }
  return saved;
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

function percentile(sorted, fraction) {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1,
    Math.max(0, Math.ceil(fraction * sorted.length) - 1));
  return sorted[index];
}

function round(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return value;
  return Math.round(value * 1000) / 1000;
}

function jpegDimensions(body) {
  if (!body || body.length < 4 || body[0] !== 0xff || body[1] !== 0xd8) return null;
  let offset = 2;
  while (offset + 9 < body.length) {
    if (body[offset] !== 0xff) return null;
    let marker = body[offset + 1];
    while (marker === 0xff) {
      offset++;
      marker = body[offset + 1];
    }
    offset += 2;
    if (marker === 0xd9 || marker === 0xda) break;
    const length = body.readUInt16BE(offset);
    if (length < 2 || offset + length > body.length) return null;
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 &&
      marker !== 0xc8 && marker !== 0xcc) {
      const height = body.readUInt16BE(offset + 3);
      const width = body.readUInt16BE(offset + 5);
      return { width, height };
    }
    offset += length;
  }
  return null;
}

function buildReport(data) {
  return {
    session,
    checks,
    failedReason,
    shutdownRequested: shutdown,
    passed: !failedReason,
    ...(data ?? {}),
    actionReceipts: receipts,
  };
}

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(overallTimer);
  if (error) {
    failedReason = error.message;
    console.error(`[bg] fail: ${failedReason}`);
  }
  let data = null;
  try {
    data = currentResult;
  } catch {
    data = null;
  }
  const report = buildReport(data);
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[bg] checks=${checks.length} passed=${!failedReason}`);

  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "background-check" });
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

let currentResult = null;

function closeAndExit() {
  clearTimeout(overallTimer);
  if (socketRef) socketRef.destroy();
  server.close(() => process.exit(failedReason ? 2 : 0));
}

overallTimer = setTimeout(() => {
  finish(new Error(`timeout after ${timeoutMs} ms; state=${lastState?.tick ?? "-"} observations=${allObservations.length}`));
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[bg] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run()
  .then((result) => {
    currentResult = result;
    finish(null);
  })
  .catch((error) => finish(error));

