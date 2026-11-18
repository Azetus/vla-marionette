#!/usr/bin/env node
/**
 * G4 state check.
 *
 * Listens for the mod, waits for SessionInfo and a run of State messages, and
 * verifies the no-world and in-world shapes, capture status, session id, and
 * monotonic tick. Saves a summary under --out.
 *
 * Usage:
 *   node tools/g4-state-check.mjs --states 30 --out runs/g4-state
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const targetStates = Number(arg("states", "30"));
const timeoutMs = Number(arg("timeout", "60000"));
const outDir = arg("out", "runs/g4-state");

fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let session = null;
let states = 0;
let inWorldStates = 0;
let validCaptureStates = 0;
let noWorldReason = null;
let firstInWorld = null;
let lastState = null;
let firstReceive = 0;
let lastReceive = 0;
let lastTick = -1;
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
        console.error("[g4] invalid frame lengths");
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
        console.log(`[g4] SessionInfo capabilities=${JSON.stringify(header.capabilities)}`);
        continue;
      }
      if (header.type !== "State") continue;

      states++;
      const now = Date.now();
      if (states === 1) firstReceive = now;
      lastReceive = now;
      lastState = header;

      if (session && header.session_id !== session.session_id) {
        console.error("[g4] state session id mismatch");
        failed = true;
      }
      if (typeof header.tick !== "number" || header.tick < lastTick) {
        console.error(`[g4] tick not monotonic: ${lastTick} -> ${header.tick}`);
        failed = true;
      }
      lastTick = header.tick;

      const capture = header.capture;
      if (!capture || typeof capture.valid !== "boolean") {
        console.error("[g4] missing capture block");
        failed = true;
      } else if (capture.valid) {
        validCaptureStates++;
      }

      if (header.in_world) {
        inWorldStates++;
        if (typeof header.dimension !== "string" ||
            typeof header.x !== "number" ||
            typeof header.y !== "number" ||
            typeof header.z !== "number" ||
            typeof header.health !== "number" ||
            typeof header.hotbar !== "number") {
          console.error("[g4] incomplete in-world state");
          failed = true;
        }
        if (!firstInWorld) {
          firstInWorld = header;
          console.log(`[g4] first in-world state tick=${header.tick} pos=(${header.x},${header.y},${header.z}) dimension=${header.dimension} capture=${capture?.valid}/${capture?.reason}`);
        }
      } else if (noWorldReason === null && capture && typeof capture.reason === "string") {
        noWorldReason = capture.reason;
        console.log(`[g4] first no-world state tick=${header.tick} reason=${capture.reason} screen=${header.screen}`);
      }

      if (states % 10 === 0) {
        console.log(`[g4] states=${states} tick=${header.tick} in_world=${header.in_world} capture=${capture?.valid}/${capture?.reason}`);
      }
      if (states >= targetStates && inWorldStates > 0) {
        finish();
        return;
      }
    }
  });
  socket.on("error", (error) => {
    console.error(`[g4] socket error: ${error.message}`);
  });
});

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  const wall = firstReceive > 0 ? (lastReceive - firstReceive) / 1000 : 0;
  const rate = wall > 0 ? (states - 1) / wall : 0;
  const summary = {
    states,
    inWorldStates,
    validCaptureStates,
    noWorldReason,
    stateRateHz: Number(rate.toFixed(2)),
    session,
    firstInWorld,
    lastState,
    outDir,
    passed: states >= targetStates && inWorldStates > 0 && validCaptureStates > 0 && !failed,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));
  console.log(`[g4] states=${states} inWorld=${inWorldStates} captureValid=${validCaptureStates} rate=${summary.stateRateHz}Hz passed=${summary.passed}`);
  if (clientSocket) clientSocket.destroy();
  server.close(() => process.exit(summary.passed ? 0 : 2));
}

timer = setTimeout(() => {
  console.error(`[g4] timeout after ${timeoutMs} ms; states=${states} inWorld=${inWorldStates}`);
  failed = true;
  finish();
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[g4] listening on 127.0.0.1:${port}, target=${targetStates} states`);
});
