#!/usr/bin/env node
/**
 * G5 raw-input + forward check.
 *
 * Waits for a stable in-world state, sends one six-tick ActionChunk holding
 * GLFW W (87), verifies received/applied/ended, and checks net displacement
 * against the pre-action yaw.
 *
 * Usage:
 *   node tools/g5-action-check.mjs --out runs/g5-action
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
const outDir = arg("out", "runs/g5-action");
const frameCount = Number(arg("frames", "6"));

fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let before = null;
let after = null;
let sent = false;
let startTick = 0;
let endedTick = 0;
let receipts = [];
let observations = 0;
let failed = false;
let finished = false;
let clientSocket = null;
let timer;

const server = net.createServer((socket) => {
  clientSocket = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      if (headerLen > frameLen - 4) {
        console.error("[g5] invalid frame lengths");
        failed = true;
        finish();
        return;
      }
      const total = 4 + frameLen;
      if (buffer.length < total) return;
      const header = JSON.parse(buffer.subarray(8, 8 + headerLen).toString("utf8"));
      buffer = buffer.subarray(total);

      if (header.type === "SessionInfo") {
        session = header;
        console.log(`[g5] SessionInfo capabilities=${JSON.stringify(header.capabilities)}`);
        continue;
      }
      if (header.type === "Observation") {
        observations++;
        continue;
      }
      if (header.type === "ActionReceipt") {
        receipts.push(header);
        console.log(`[g5] receipt ${header.status} seq=${header.seq} tick=${header.tick ?? "-"} reason=${header.reason ?? "-"}`);
        if (header.status === "rejected") failed = true;
        if (header.status === "ended") {
          endedTick = header.tick;
          if (!startTick || endedTick !== startTick + frameCount) {
            console.error(`[g5] bad ended tick ${endedTick}, expected ${startTick + frameCount}`);
            failed = true;
          }
        }
        continue;
      }
      if (header.type !== "State") continue;

      lastState = header;
      if (!sent && header.in_world && header.screen_open === false &&
          header.on_ground === true && header.health > 0 && header.tick > 10) {
        before = header;
        startTick = header.tick + 4;
        const frames = Array.from({ length: frameCount }, () => ({ keys: [87] }));
        const chunk = { type: "ActionChunk", seq: 1, start_tick: startTick, frames };
        sendFrame(chunk);
        sent = true;
        console.log(`[g5] sent W frames=${frameCount} start_tick=${startTick}`);
      }

      if (sent && endedTick > 0 && header.tick >= endedTick) {
        after = header;
        finish();
        return;
      }
    }
  });
  socket.on("error", (error) => {
    console.error(`[g5] socket error: ${error.message}`);
    failed = true;
  });
});

function sendFrame(header) {
  const json = Buffer.from(JSON.stringify(header), "utf8");
  const frame = Buffer.alloc(8 + json.length);
  frame.writeUInt32BE(4 + json.length, 0);
  frame.writeUInt32BE(json.length, 4);
  json.copy(frame, 8);
  clientSocket.write(frame);
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
  let forward = null;
  if (before && after) {
    const yaw = before.yaw * Math.PI / 180.0;
    forward = { x: -Math.sin(yaw), z: Math.cos(yaw) };
    const dx = after.x - before.x;
    const dz = after.z - before.z;
    distance = Math.hypot(dx, dz);
    dot = distance > 1e-6 ? (dx * forward.x + dz * forward.z) / distance : 0;
  }

  const summary = {
    session,
    sent,
    before,
    after,
    startTick,
    endedTick,
    observedTicks: lastState?.tick ?? null,
    observations,
    receipts,
    distance,
    directionDot: dot,
    forward,
    passed: sent && received && applied && ended && !failed &&
      distance >= 0.10 && dot >= 0.80,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));
  console.log(`[g5] received=${received} applied=${applied} ended=${ended} distance=${summary.distance.toFixed(3)} dot=${summary.directionDot.toFixed(3)} passed=${summary.passed}`);
  if (clientSocket) clientSocket.destroy();
  server.close(() => process.exit(summary.passed ? 0 : 2));
}

timer = setTimeout(() => {
  console.error(`[g5] timeout after ${timeoutMs} ms; sent=${sent} receipts=${receipts.length}`);
  failed = true;
  finish();
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[g5] listening on 127.0.0.1:${port}`);
});
