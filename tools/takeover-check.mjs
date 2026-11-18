#!/usr/bin/env node
/**
 * Manual takeover receipt test.
 *
 * Waits for a stable world, holds W with back-to-back chunks, and asks the
 * operator to press ESC. Then verifies:
 *   - interrupted/manual_takeover for the active/scheduled action;
 *   - rejected/manual_takeover for a chunk sent while paused;
 *   - a new chunk accepted after returning to the world.
 *
 * Usage:
 *   node tools/takeover-check.mjs --out runs/takeover --start-delay 5
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const outDir = path.resolve(arg("out", "runs/takeover"));
const timeoutMs = Number(arg("timeout", "90000"));
const startDelayMs = Number(arg("start-delay", "5")) * 1000;
const framesPerChunk = Number(arg("frames", "10"));

fs.mkdirSync(outDir, { recursive: true });

let buffer = Buffer.alloc(0);
let socketRef = null;
let session = null;
let state = null;
let phase = "wait_world";
let startAt = 0;
let nextStart = 0;
let seq = 0;
let activeSeq = 0;
let interrupted = [];
let pauseSent = false;
let pauseRejected = null;
let resumeReport = null;
let finished = false;
let failed = false;
let timer;
let startTimer;

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
      buffer = buffer.subarray(total);
      handle(header);
      if (finished) return;
    }
  });
});

function handle(header) {
  if (header.type === "SessionInfo") {
    session = header;
    console.log(`[takeover] SessionInfo caps=${JSON.stringify(header.capabilities)}`);
    return;
  }

  if (header.type === "ActionReceipt") {
    console.log(`[takeover] receipt ${header.status} seq=${header.seq} tick=${header.tick ?? "-"} reason=${header.reason ?? "-"}`);
    if (header.status === "interrupted" && header.reason === "manual_takeover") {
      interrupted.push(header);
      if (phase === "running" || phase === "paused") {
        phase = "paused";
      }
      if (!pauseSent && state) {
        pauseSent = true;
        seq++;
        sendW(seq, state.tick + 4);
        console.log(`[takeover] interrupted; sent chunk while paused seq=${seq}`);
      }
    }
    if (header.status === "rejected" && header.reason === "manual_takeover") {
      pauseRejected = header;
      console.log("[takeover] pause rejection observed");
    }
    if (header.status === "applied" && phase === "running") {
      activeSeq = header.seq;
      // Keep W held across chunk boundaries until ESC.
      if (seq < 20 && !interrupted.length) {
        sendW(++seq, nextStart);
        nextStart += framesPerChunk;
      }
    }
    if (header.status === "ended" && phase === "resume" && header.seq === activeSeq) {
      resumeReport = header;
      finish();
    }
    return;
  }

  if (header.type !== "State") return;
  state = header;

  if (phase === "wait_world" && state.in_world && state.tick > 10) {
    phase = "starting";
    console.log(`[takeover] world ready; W starts in ${startDelayMs / 1000}s — press ESC after movement starts`);
    startTimer = setTimeout(startW, startDelayMs);
    return;
  }

  if (phase === "paused" && pauseRejected && state.screen === null &&
      state.input_locked === true) {
    phase = "resume";
    seq++;
    activeSeq = seq;
    sendW(seq, state.tick + 4);
    console.log(`[takeover] re-armed; sent resume chunk seq=${seq}`);
    return;
  }
}

function startW() {
  if (phase !== "starting") return;
  phase = "running";
  seq = 1;
  activeSeq = 1;
  startAt = state.tick + 4;
  nextStart = startAt + framesPerChunk;
  sendW(seq, startAt);
  console.log(`[takeover] ACTION ACTIVE start=${startAt}; press ESC now`);
}

function sendW(chunkSeq, startTick) {
  const frames = Array.from({ length: framesPerChunk }, () => ({ keys: [87] }));
  sendFrame({ type: "ActionChunk", seq: chunkSeq, start_tick: startTick, frames });
}

function sendFrame(header) {
  const json = Buffer.from(JSON.stringify(header), "utf8");
  const frame = Buffer.alloc(8 + json.length);
  frame.writeUInt32BE(4 + json.length, 0);
  frame.writeUInt32BE(json.length, 4);
  json.copy(frame, 8);
  socketRef.write(frame);
}

function fail(reason) {
  if (failed) return;
  failed = true;
  console.error(`[takeover] fail: ${reason}`);
  finish();
}

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  clearTimeout(startTimer);
  const passed = interrupted.length > 0 && Boolean(pauseRejected) && Boolean(resumeReport) && !failed;
  const summary = {
    session,
    interrupted,
    pauseRejected,
    resumeReport,
    finalState: state,
    passed,
  };
  fs.writeFileSync(path.join(outDir, "summary.json"), JSON.stringify(summary, null, 2));
  console.log(`[takeover] interrupted=${interrupted.length} pauseRejected=${Boolean(pauseRejected)} resumeEnded=${Boolean(resumeReport)} passed=${passed}`);
  if (socketRef) socketRef.destroy();
  server.close(() => process.exit(passed ? 0 : 2));
}

timer = setTimeout(() => fail(`timeout; phase=${phase}`), timeoutMs);
server.listen(port, "127.0.0.1", () => {
  console.log(`[takeover] listening on 127.0.0.1:${port}; out=${outDir}`);
});
