#!/usr/bin/env node
/**
 * G3 capture check.
 *
 * Listens for the mod (the mod dials out), waits for SessionInfo plus a number
 * of Observation frames, verifies every JPEG decodes to 854x480, estimates the
 * capture rate, and saves one frame for manual inspection.
 *
 * Usage:
 *   node tools/g3-capture-check.mjs --frames 20 --out runs/g3-capture
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const targetFrames = Number(arg("frames", "20"));
const timeoutMs = Number(arg("timeout", "60000"));
const outDir = arg("out", "runs/g3-capture");

function jpegSize(buffer) {
  if (buffer.length < 4 || buffer[0] !== 0xff || buffer[1] !== 0xd8) return null;
  let i = 2;
  while (i + 3 < buffer.length) {
    if (buffer[i] !== 0xff) {
      i++;
      continue;
    }
    while (i < buffer.length && buffer[i] === 0xff) i++;
    const marker = buffer[i++];
    if (marker === 0xd8 || marker === 0xd9) continue;
    if (i + 2 > buffer.length) break;
    const length = (buffer[i] << 8) | buffer[i + 1];
    i += 2;
    if (marker >= 0xc0 && marker <= 0xc3) {
      if (i + 5 > buffer.length) break;
      const height = (buffer[i + 1] << 8) | buffer[i + 2];
      const width = (buffer[i + 3] << 8) | buffer[i + 4];
      return { width, height };
    }
    i += length - 2;
  }
  return null;
}

fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let session = null;
let frames = 0;
let firstReceive = 0;
let lastReceive = 0;
let firstSaved = false;
let failed = false;
let finished = false;
let timer;
let clientSocket = null;

const server = net.createServer((socket) => {
  clientSocket = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      if (headerLen > frameLen - 4) {
        console.error("[g3] invalid frame lengths");
        failed = true;
        return;
      }
      const total = 4 + frameLen;
      if (buffer.length < total) return;

      const header = JSON.parse(buffer.subarray(8, 8 + headerLen).toString("utf8"));
      const body = buffer.subarray(8 + headerLen, total);
      buffer = buffer.subarray(total);

      if (header.type === "SessionInfo") {
        session = header;
        console.log(`[g3] SessionInfo protocol=${header.protocol_version} image=${header.image.width}x${header.image.height} ${header.image.pixel_format} capabilities=${JSON.stringify(header.capabilities)}`);
        continue;
      }
      if (header.type !== "Observation") continue;

      const size = jpegSize(body);
      frames++;
      const now = Date.now();
      if (frames === 1) firstReceive = now;
      lastReceive = now;

      if (!size || size.width !== 854 || size.height !== 480) {
        console.error(`[g3] frame ${header.obs_id} bad JPEG size: ${JSON.stringify(size)}`);
        failed = true;
      }
      if (!firstSaved) {
        fs.writeFileSync(path.join(outDir, "first-frame.jpg"), body);
        firstSaved = true;
      }
      if (frames % 5 === 0) {
        console.log(`[g3] frames=${frames} obs_id=${header.obs_id} tick=${header.state_tick} bytes=${body.length} jpeg=${size?.width}x${size?.height}`);
      }
      if (frames >= targetFrames) {
        finish();
        return;
      }
    }
  });
  socket.on("error", (error) => {
    console.error(`[g3] socket error: ${error.message}`);
  });
});

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  const wall = firstReceive > 0 ? (lastReceive - firstReceive) / 1000 : 0;
  const fps = wall > 0 ? (frames - 1) / wall : 0;
  const summary = {
    frames,
    fps: Number(fps.toFixed(2)),
    session,
    outDir,
    passed: frames >= targetFrames && !failed && fps >= 4.0,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));
  console.log(`[g3] frames=${frames} fps=${summary.fps} passed=${summary.passed}`);
  if (clientSocket) clientSocket.destroy();
  server.close(() => process.exit(summary.passed ? 0 : 2));
}

timer = setTimeout(() => {
  console.error(`[g3] timeout after ${timeoutMs} ms; frames=${frames}`);
  failed = true;
  finish();
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[g3] listening on 127.0.0.1:${port}, target=${targetFrames} frames`);
});
