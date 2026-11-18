#!/usr/bin/env node
/**
 * M1 acceptance host.
 *
 * One session: verify SessionInfo, take a first JPEG, wait for a stable
 * in-world state, send one six-tick W ActionChunk, verify the receipt chain,
 * then verify post-action state and a new JPEG. No dependencies.
 *
 * Usage:
 *   node tools/m1-acceptance.mjs --out runs/m1-.../run1 [--shutdown]
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "60000"));
const outDir = path.resolve(arg("out", "runs/m1-acceptance"));
const frameCount = Number(arg("frames", "6"));
const maxDy = Number(arg("max-dy", "0.20"));
const shutdown = process.argv.includes("--shutdown");

fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let socketRef = null;
let session = null;
let firstFrame = null;
let firstFrameMeta = null;
let afterFrame = null;
let afterFrameMeta = null;
let lastState = null;
let before = null;
let after = null;
let heldDuringAction = null;
let actionSent = false;
let startTick = 0;
let endedTick = 0;
let receipts = [];
let observations = 0;
let failedReason = null;
let finished = false;
let timer;
let shutdownTimer;

const server = net.createServer((socket) => {
  socketRef = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      if (headerLen > frameLen - 4) {
        fail("invalid frame lengths");
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
  socket.on("error", (error) => fail(`socket error: ${error.message}`));
});

function handleMessage(header, body) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      if (header.image?.width !== 854 || header.image?.height !== 480 || header.image?.pixel_format !== "jpeg") {
        fail("bad image spec");
      }
      if (header.render?.width !== 640 || header.render?.height !== 360 ||
          header.window?.width !== 640 || header.window?.height !== 360) {
        fail("bad render/window spec");
      }
      for (const capability of ["observation", "state", "raw_input"]) {
        if (!header.capabilities?.includes(capability)) fail(`missing capability: ${capability}`);
      }
      console.log(`[m1] SessionInfo capabilities=${JSON.stringify(header.capabilities)}`);
      break;
    case "Observation": {
      observations++;
      const dims = jpegDimensions(body);
      if (!dims) {
        fail("invalid JPEG body");
        return;
      }
      if (dims.width !== 854 || dims.height !== 480) fail(`JPEG ${dims.width}x${dims.height}`);
      if (!firstFrame) {
        firstFrame = Buffer.from(body);
        firstFrameMeta = { ...header, jpeg: dims };
        console.log(`[m1] first JPEG obs=${header.obs_id} tick=${header.state_tick} ${dims.width}x${dims.height}`);
      }
      if (actionSent && endedTick > 0 && header.state_tick >= endedTick && !afterFrame) {
        afterFrame = Buffer.from(body);
        afterFrameMeta = { ...header, jpeg: dims };
        console.log(`[m1] post-action JPEG obs=${header.obs_id} tick=${header.state_tick}`);
        maybeFinish();
      }
      break;
    }
    case "State":
      lastState = header;
      if (!actionSent && header.in_world && header.screen_open === false &&
          header.on_ground === true && header.health > 0 && header.capture?.valid === true &&
          header.tick > 10) {
        before = header;
        startTick = header.tick + 4;
        sendAction();
        actionSent = true;
        console.log(`[m1] sent W frames=${frameCount} start=${startTick}`);
      }
      if (actionSent && !heldDuringAction && header.tick >= startTick &&
          (endedTick === 0 || header.tick < endedTick) &&
          header.held?.keys?.includes(87)) {
        heldDuringAction = { tick: header.tick, held: header.held, input_locked: header.input_locked };
        console.log(`[m1] held during action tick=${header.tick} keys=${JSON.stringify(header.held.keys)} locked=${header.input_locked}`);
      }
      if (actionSent && endedTick > 0 && header.tick >= endedTick &&
          header.on_ground === true && !after) {
        after = header;
        console.log(`[m1] post-action State tick=${header.tick} y=${header.y}`);
        maybeFinish();
      }
      break;
    case "ActionReceipt":
      receipts.push(header);
      console.log(`[m1] receipt ${header.status} seq=${header.seq} tick=${header.tick ?? "-"} reason=${header.reason ?? "-"}`);
      if (header.status === "rejected") fail(`rejected: ${header.reason}`);
      if (header.status === "ended") {
        endedTick = header.tick;
        if (header.tick !== startTick + frameCount) fail(`bad ended tick ${header.tick}`);
      }
      break;
    case "ShutdownAck":
      console.log(`[m1] ShutdownAck reason=${header.reason} save=${header.save}`);
      closeAndExit();
      break;
    default:
      break;
  }
}

function sendAction() {
  const frames = Array.from({ length: frameCount }, () => ({ keys: [87] }));
  sendFrame({ type: "ActionChunk", seq: 1, start_tick: startTick, frames });
}

function sendFrame(header, body = null) {
  const json = Buffer.from(JSON.stringify(header), "utf8");
  const bodyBuf = body ?? Buffer.alloc(0);
  const frame = Buffer.alloc(8 + json.length + bodyBuf.length);
  frame.writeUInt32BE(4 + json.length + bodyBuf.length, 0);
  frame.writeUInt32BE(json.length, 4);
  json.copy(frame, 8);
  bodyBuf.copy(frame, 8 + json.length);
  socketRef.write(frame);
}

function maybeFinish() {
  if (before && after && firstFrame && afterFrame && endedTick > 0) {
    finish();
  }
}

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);

  const received = receipts.some((r) => r.status === "received");
  const applied = receipts.some((r) => r.status === "applied" && r.tick === startTick);
  const ended = receipts.some((r) => r.status === "ended" && r.tick === startTick + frameCount);
  let distance = 0;
  let dot = 0;
  let dy = 0;
  let forward = null;
  if (before && after) {
    const yaw = before.yaw * Math.PI / 180.0;
    forward = { x: -Math.sin(yaw), z: Math.cos(yaw) };
    const dx = after.x - before.x;
    const dz = after.z - before.z;
    dy = after.y - before.y;
    distance = Math.hypot(dx, dz);
    dot = distance > 1e-6 ? (dx * forward.x + dz * forward.z) / distance : 0;
  }
  const afterFrameFresh = Boolean(afterFrameMeta && endedTick > 0 &&
    afterFrameMeta.state_tick >= endedTick &&
    firstFrameMeta && afterFrameMeta.obs_id > firstFrameMeta.obs_id);
  const passed = !failedReason && received && applied && ended && afterFrameFresh &&
    distance >= 0.10 && dot >= 0.80 && Math.abs(dy) <= maxDy &&
    after?.on_ground === true && after?.screen_open === false;

  const report = {
    session,
    firstFrame: firstFrameMeta,
    afterFrame: afterFrameMeta,
    observations,
    before,
    heldDuringAction,
    after,
    startTick,
    endedTick,
    receipts,
    distance,
    directionDot: dot,
    dy,
    maxDy,
    forward,
    shutdownRequested: shutdown,
    failedReason,
    passed,
  };
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  if (firstFrame) fs.writeFileSync(path.join(outDir, "first.jpg"), firstFrame);
  if (afterFrame) fs.writeFileSync(path.join(outDir, "after.jpg"), afterFrame);
  console.log(`[m1] received=${received} applied=${applied} ended=${ended} newFrame=${afterFrameFresh} distance=${distance.toFixed(3)} dot=${dot.toFixed(3)} dy=${dy.toFixed(3)} passed=${passed}`);

  if (shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "m1-acceptance" });
    shutdownTimer = setTimeout(() => closeAndExit(), 10000);
  } else {
    closeAndExit();
  }
}

function fail(reason) {
  if (failedReason) return;
  failedReason = reason;
  console.error(`[m1] fail: ${reason}`);
  // A session-level failure should still produce a report.
  if (!finished) {
    if (startTick && endedTick && !after) {
      // Give the normal state/frame path a chance only when failure is a late receipt issue.
    }
    finish();
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
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      const height = body.readUInt16BE(offset + 3);
      const width = body.readUInt16BE(offset + 5);
      return { width, height };
    }
    offset += length;
  }
  return null;
}

timer = setTimeout(() => {
  fail(`timeout after ${timeoutMs} ms; sent=${actionSent} state=${lastState?.tick ?? "-"} receipts=${receipts.length}`);
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[m1] listening on 127.0.0.1:${port}, out=${outDir}`);
});
