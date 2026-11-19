#!/usr/bin/env node
/**
 * FOV config check host.
 *
 * One session: read QuerySettings, save one Observation frame, teleport to an
 * open flat area, then hold forward+sprint for 20 ticks and read QuerySettings
 * again. In fixed mode the reported vertical FOV must not change; in vanilla
 * mode the value is recorded for inspection. With --spyglass, give the player a
 * spyglass, use it, and verify that fixed mode still pins the FOV while vanilla
 * mode zooms.
 *
 * Usage:
 *   node tools/fov-check.mjs --port 25580 --out runs/fov-check \
 *     [--expect-fov 30] [--expect-mode fixed] [--spyglass] \
 *     [--frame runs/fov-check/fov.jpg] [--shutdown]
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "90000"));
const outDir = path.resolve(arg("out", "runs/fov-check"));
const expectFov = arg("expect-fov", "");
const expectMode = arg("expect-mode", "");
const framePath = path.resolve(arg("frame", path.join(outDir, "fov.jpg")));
const shutdown = process.argv.includes("--shutdown");
const spyglass = process.argv.includes("--spyglass");

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(path.dirname(framePath), { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let finished = false;
let failedReason = null;
let shutdownTimer;
let requestCounter = 200;
let frameSaved = false;

const pending = new Map();
const receipts = [];
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
      const body = buffer.subarray(8 + headerLen, total);
      buffer = buffer.subarray(total);
      handleMessage(header, body);
      if (finished) return;
    }
  });
  socket.on("error", (error) => finish(error));
});

function handleMessage(header, body = Buffer.alloc(0)) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      break;
    case "State":
      lastState = header;
      break;
    case "Observation":
      if (!frameSaved && body.length > 2 && body[0] === 0xff && body[1] === 0xd8) {
        fs.writeFileSync(framePath, body);
        frameSaved = true;
        console.log(`[fov] saved frame ${framePath} (${body.length} bytes)`);
      }
      break;
    case "ActionReceipt":
      receipts.push(header);
      break;
    case "QuerySettingsResult": {
      const id = header.request_id;
      if (id !== null && pending.has(id)) {
        const resolve = pending.get(id);
        pending.delete(id);
        resolve(header);
      }
      break;
    }
    case "ChatAck": {
      const id = header.request_id;
      if (id !== null && pending.has(id)) {
        const resolve = pending.get(id);
        pending.delete(id);
        resolve(header);
      }
      break;
    }
    case "ShutdownAck":
      console.log(`[fov] ShutdownAck save=${header.save} reason=${header.reason}`);
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

function nextId() {
  return requestCounter++;
}

function sendRequest(header, waitMs = 10000) {
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

function querySettings() {
  const id = nextId();
  return sendRequest({ type: "QuerySettings", request_id: id });
}

function chat(text) {
  const id = nextId();
  return sendRequest({ type: "Chat", request_id: id, text });
}

function sendActionChunk(seq, frames) {
  const startTick = (lastState?.tick ?? 0) + 10;
  sendFrame({ type: "ActionChunk", seq, start_tick: startTick, frames });
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

async function goToOpenArea() {
  const ack = await chat("/tp @s 1000 -60 1000 0 0");
  check(ack.ok === true && ack.reason === null, "tp to open area");
  await waitFor(() => (lastState && Math.abs(lastState.x - 1000) < 3 &&
    Math.abs(lastState.z - 1000) < 3 ? lastState : null), "teleport state", 10000);
  await sleep(800);
}

async function runSpyglass(before) {
  const use = before.keybinds?.["key.use"]?.code ?? 1;
  const hotbar1 = before.keybinds?.["key.hotbar.1"]?.code ?? 49;

  let ack = await chat("/clear @s");
  check(ack.ok === true && ack.reason === null, "clear inventory for spyglass");
  await sleep(300);
  ack = await chat("/give @s minecraft:spyglass 1");
  check(ack.ok === true && ack.reason === null, "give spyglass");
  await sleep(400);

  const hotbarSeq = 2;
  sendActionChunk(hotbarSeq, [{ keys: [hotbar1] }]);
  await waitFor(() => receipts.some((item) =>
    item.seq === hotbarSeq && item.status === "applied"), "hotbar applied", 10000);
  await waitFor(() => receipts.some((item) =>
    item.seq === hotbarSeq && item.status === "ended"), "hotbar ended", 15000);

  const useSeq = 3;
  sendActionChunk(useSeq, Array.from({ length: 15 }, () => ({ buttons: [use] })));
  await waitFor(() => receipts.some((item) =>
    item.seq === useSeq && item.status === "applied"), "spyglass applied", 10000);
  await sleep(300);
  const scoped = await querySettings();
  check(scoped.scoping === true, `spyglass scoping active: ${scoped.scoping}`);
  if (before.fov_mode === "fixed") {
    check(Math.abs(scoped.fov - before.fov) <= 0.01,
      `fixed fov while scoping: ${scoped.fov} == ${before.fov}`);
  } else {
    check(scoped.fov < before.fov - 1.0,
      `vanilla fov while scoping decreased: ${scoped.fov} < ${before.fov}`);
  }

  await waitFor(() => receipts.some((item) =>
    item.seq === useSeq && item.status === "ended"), "spyglass ended", 15000);
  ack = await chat("/clear @s");
  check(ack.ok === true && ack.reason === null, "clear spyglass");
  await sleep(200);
  return scoped;
}

async function run() {
  await waitFor(() => session, "SessionInfo");
  const state0 = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.tick > 10 && lastState.input_locked === true ? lastState : null),
    "stable in-world state");

  const before = await querySettings();
  check(before.ok === true && before.reason === null, "settings query ok");
  check(["fixed", "vanilla"].includes(before.fov_mode),
    `fov_mode is fixed or vanilla: ${before.fov_mode}`);
  check(typeof before.fov === "number" && Number.isFinite(before.fov) &&
    before.fov > 0 && before.fov < 180, `fov is finite: ${before.fov}`);
  check(typeof before.fov_effect_scale === "number" &&
    before.fov_effect_scale >= 0 && before.fov_effect_scale <= 1,
    `fov_effect_scale in 0..1: ${before.fov_effect_scale}`);
  if (expectMode) {
    check(before.fov_mode === expectMode,
      `fov_mode ${before.fov_mode} == expected ${expectMode}`);
  }
  if (expectFov) {
    const expected = Number(expectFov);
    check(before.fov_mode === "fixed",
      `fixed mode expected for --expect-fov, got ${before.fov_mode}`);
    check(Math.abs(before.fov - expected) <= 0.01,
      `fov ${before.fov} == expected ${expected}`);
  }

  await waitFor(() => frameSaved, "Observation frame", 10000);

  await goToOpenArea();
  const sprintStart = { x: lastState.x, z: lastState.z };

  const forward = before.keybinds?.["key.forward"]?.code ?? 87;
  const sprint = before.keybinds?.["key.sprint"]?.code ?? 341;
  const seq = 1;
  const frames = Array.from({ length: 20 }, () => ({ keys: [forward, sprint] }));
  sendActionChunk(seq, frames);

  await waitFor(() => receipts.some((item) => item.seq === seq && item.status === "applied"),
    "applied receipt", 10000);
  const during = await querySettings();

  if (before.fov_mode === "fixed") {
    check(Math.abs(during.fov - before.fov) <= 0.01,
      `fixed fov while sprinting: ${during.fov} == ${before.fov}`);
  } else {
    console.log(`[fov] vanilla fov while sprinting: ${during.fov} `
      + `(base ${before.fov}, effect_scale ${before.fov_effect_scale})`);
    check(during.fov >= before.fov - 0.01,
      `vanilla fov did not decrease while sprinting: ${during.fov}`);
  }

  await waitFor(() => receipts.some((item) => item.seq === seq && item.status === "ended"),
    "ended receipt", 15000);
  const ended = receipts.find((item) => item.seq === seq && item.status === "ended");
  await waitFor(() => (lastState && lastState.tick >= ended.tick + 2 ? lastState : null),
    "state after sprint", 5000);
  const sprintEnd = { x: lastState.x, z: lastState.z };
  const after = await querySettings();

  if (before.fov_mode === "fixed") {
    check(Math.abs(after.fov - before.fov) <= 0.01,
      `fixed fov after sprinting: ${after.fov} == ${before.fov}`);
  }

  const moved = Math.hypot(sprintEnd.x - sprintStart.x, sprintEnd.z - sprintStart.z);
  check(moved > 1.0, `player moved during sprint chunk: ${moved.toFixed(2)} blocks`);

  let scoped = null;
  if (spyglass) {
    scoped = await runSpyglass(before);
  }

  return {
    session,
    before,
    during,
    after,
    scoped,
    ended,
    state0: { x: state0.x, y: state0.y, z: state0.z, tick: state0.tick },
    sprintStart,
    sprintEnd,
    moved: Math.round(moved * 1000) / 1000,
    framePath,
    checks,
  };
}

function finish(error, result) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  if (error) {
    failedReason = error.message;
    console.error(`[fov] fail: ${failedReason}`);
  }
  const report = {
    ...(result ?? {}),
    receipts,
    checks,
    failedReason,
    shutdownRequested: shutdown,
    passed: !failedReason,
  };
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[fov] checks=${checks.length} passed=${!failedReason}`);
  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "fov-check" });
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
  console.log(`[fov] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run().then((result) => finish(null, result)).catch((error) => finish(error));
