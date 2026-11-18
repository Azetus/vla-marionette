#!/usr/bin/env node
/**
 * G5 GUI raw-input smoke.
 *
 * From a stable in-world state: E opens the inventory, a mouse frame is
 * injected while the Screen is open, E closes it. Verifies only that state
 * reports the Screen transitions and all receipts complete; it does not touch
 * item slots.
 *
 * Usage:
 *   node tools/g5-gui-check.mjs --out runs/g5-gui
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
const outDir = arg("out", "runs/g5-gui");
fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let socketRef = null;
let session = null;
let state = null;
let phase = "wait_world";
let seq = 0;
let startTick = 0;
let openedState = null;
let closedState = null;
let mouseEnded = false;
let openEnded = false;
let failed = false;
let finished = false;
let timer;

const server = net.createServer((socket) => {
  socketRef = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 8) {
      const frameLen = buffer.readUInt32BE(0);
      const headerLen = buffer.readUInt32BE(4);
      const total = 4 + frameLen;
      if (buffer.length < total) return;
      const header = JSON.parse(buffer.subarray(8, 8 + headerLen).toString("utf8"));
      buffer = buffer.subarray(total);

      if (header.type === "SessionInfo") {
        session = header;
        console.log(`[g5-gui] SessionInfo capabilities=${JSON.stringify(header.capabilities)}`);
        continue;
      }
      if (header.type === "ActionReceipt") {
        console.log(`[g5-gui] receipt ${header.status} seq=${header.seq} tick=${header.tick ?? "-"} reason=${header.reason ?? "-"}`);
        if (header.status === "rejected") failed = true;
        if (header.status === "ended" && header.seq === 1) openEnded = true;
        if (header.status === "ended" && header.seq === 2) mouseEnded = true;
        continue;
      }
      if (header.type !== "State") continue;
      state = header;

      if (phase === "wait_world" && state.in_world && !state.screen_open && state.tick > 10) {
        phase = "opening";
        seq = 1;
        startTick = state.tick + 4;
        sendChunk(seq, startTick, [
          { keys: [69] }, null, null, null,
        ]);
        console.log(`[g5-gui] sent open seq=1 start=${startTick}`);
        continue;
      }

      if (phase === "opening" && openEnded && state.screen === "InventoryScreen") {
        openedState = state;
        phase = "moving";
        seq = 2;
        startTick = state.tick + 4;
        sendChunk(seq, startTick, [
          { mouse: { dx: 40, dy: 20, scroll: 0 } }, null, null, null,
        ]);
        console.log(`[g5-gui] inventory open; sent mouse seq=2 start=${startTick}`);
        continue;
      }

      if (phase === "moving" && mouseEnded) {
        phase = "closing";
        seq = 3;
        startTick = state.tick + 4;
        sendChunk(seq, startTick, [
          { keys: [69] }, null, null, null,
        ]);
        console.log(`[g5-gui] mouse done; sent close seq=3 start=${startTick}`);
        continue;
      }

      if (phase === "closing" && state.screen_open === false) {
        closedState = state;
        finish();
        return;
      }
    }
  });
});

function sendChunk(seq, start, frames) {
  const header = { type: "ActionChunk", seq, start_tick: start, frames };
  const json = Buffer.from(JSON.stringify(header), "utf8");
  const frame = Buffer.alloc(8 + json.length);
  frame.writeUInt32BE(4 + json.length, 0);
  frame.writeUInt32BE(json.length, 4);
  json.copy(frame, 8);
  socketRef.write(frame);
}

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  const summary = {
    session,
    opened: openedState ? { tick: openedState.tick, screen: openedState.screen } : null,
    closed: closedState ? { tick: closedState.tick, screen: closedState.screen } : null,
    mouseEnded,
    passed: !failed && openedState?.screen === "InventoryScreen" && closedState?.screen_open === false,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));
  console.log(`[g5-gui] opened=${summary.opened?.screen} closed=${summary.closed?.screen} passed=${summary.passed}`);
  if (socketRef) socketRef.destroy();
  server.close(() => process.exit(summary.passed ? 0 : 2));
}

timer = setTimeout(() => {
  console.error(`[g5-gui] timeout phase=${phase} screen=${state?.screen}`);
  failed = true;
  finish();
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[g5-gui] listening on 127.0.0.1:${port}`);
});
