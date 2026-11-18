#!/usr/bin/env node
/**
 * Observe State.input_locked / State.held while a physical input-lock test is
 * performed. It does not send actions.
 *
 * Usage:
 *   node tools/input-lock-observe.mjs
 *
 * Then focus the Minecraft window and press W / ESC. Lines are printed when the
 * lock, held set, or screen changes.
 */

import net from "node:net";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "0"));

let buffer = Buffer.alloc(0);
let last = "";
let timer;

const server = net.createServer((socket) => {
  console.log(`[observe] client connected`);
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      if (headerLen > frameLen - 4) {
        console.error("[observe] invalid frame");
        return;
      }
      const total = 4 + frameLen;
      if (buffer.length < total) return;
      const header = JSON.parse(buffer.subarray(8, 8 + headerLen).toString("utf8"));
      buffer = buffer.subarray(total);

      if (header.type === "SessionInfo") {
        console.log(`[observe] SessionInfo capabilities=${JSON.stringify(header.capabilities)}`);
      } else if (header.type === "ActionReceipt") {
        console.log(`[observe] receipt ${header.status} seq=${header.seq} reason=${header.reason ?? "-"}`);
      } else if (header.type === "State") {
        const line = `${header.tick} locked=${header.input_locked} held=${JSON.stringify(header.held)} screen=${header.screen ?? "-"}`;
        if (line !== last) {
          console.log(`[observe] ${line}`);
          last = line;
        }
      }
    }
  });
});

if (timeoutMs > 0) {
  timer = setTimeout(() => {
    console.log("[observe] timeout");
    server.close(() => process.exit(0));
  }, timeoutMs);
}

process.on("SIGINT", () => {
  clearTimeout(timer);
  server.close(() => process.exit(0));
});

server.listen(port, "127.0.0.1", () => {
  console.log(`[observe] listening on 127.0.0.1:${port}; focus Minecraft and press W/ESC`);
});
