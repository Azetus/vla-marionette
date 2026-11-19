#!/usr/bin/env node
/**
 * Action-matrix acceptance over raw input primitives.
 *
 * Foreground mode covers movement, jump, sneak/sprint, attack, use, hotbar,
 * scroll, inventory open/click/shift-click, drop and camera. Minimized mode
 * covers the core world/GUI subset; the window must already be minimized.
 *
 * Usage:
 *   node tools/matrix-check.mjs --port 25580 --out runs/matrix [--shutdown]
 *   node tools/matrix-check.mjs --port 25580 --out runs/matrix-min --minimized [--shutdown]
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const port = Number(arg("port", "25580"));
const timeoutMs = Number(arg("timeout", "240000"));
const outDir = path.resolve(arg("out", "runs/matrix-check"));
const shutdown = process.argv.includes("--shutdown");
const minimized = process.argv.includes("--minimized");

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let finished = false;
let failedReason = null;
let shutdownAck = null;
let requestCounter = 100;
let seqCounter = 1;
let overallTimer;
let settings = null;
let keybinds = null;

const receipts = [];
const stateLog = [];
const checks = [];
const actions = [];
const pending = new Map();
const lastResponse = {};

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
  socket.on("error", (error) => {
    if (!finished) finish(error);
  });
  socket.on("close", () => {
    if (!finished && !shutdownAck) {
      finish(new Error("connection closed before the matrix check completed"));
    }
  });
});

function handleMessage(header) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      break;
    case "State":
      lastState = header;
      stateLog.push(header);
      if (stateLog.length > 2500) stateLog.shift();
      break;
    case "ActionReceipt":
      receipts.push(header);
      break;
    case "QueryBlockResult":
    case "QueryInventoryResult":
    case "QuerySettingsResult":
    case "ChatAck": {
      lastResponse[header.type] = header;
      const id = header.request_id;
      if (id !== null && pending.has(id)) {
        const resolve = pending.get(id);
        pending.delete(id);
        resolve(header);
      }
      break;
    }
    case "ShutdownAck":
      shutdownAck = header;
      console.log(`[matrix] ShutdownAck save=${header.save} reason=${header.reason}`);
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

function request(header, waitMs = 10000) {
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

function chat(text) {
  return request({ type: "Chat", request_id: nextId(), text });
}

function queryBlock(pos) {
  return request({ type: "QueryBlock", request_id: nextId(), pos });
}

function queryInventory() {
  return request({ type: "QueryInventory", request_id: nextId() });
}

function querySettings() {
  return request({ type: "QuerySettings", request_id: nextId() });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, label, waitMs = 20000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(30);
  }
  throw new Error(`timeout waiting for ${label}`);
}

function check(condition, label) {
  if (!condition) throw new Error(label);
  checks.push(label);
}

function waitReceipt(seq, status, tick, label) {
  return waitFor(() => receipts.find((receipt) =>
    receipt.seq === seq && receipt.status === status &&
    (tick === undefined || receipt.tick === tick)), label, 15000);
}

async function action(frames, label, startTick) {
  const start = startTick ?? lastState.tick + 4;
  const seq = seqCounter++;
  sendFrame({ type: "ActionChunk", seq, start_tick: start, frames });
  const outcome = await waitFor(() => receipts.find((receipt) =>
    receipt.seq === seq &&
    ["applied", "rejected", "interrupted"].includes(receipt.status)),
  `${label} outcome`, 15000);
  if (outcome.status !== "applied") {
    throw new Error(`${label}: ${outcome.status} ${outcome.reason ?? ""}`);
  }
  await waitReceipt(seq, "received", undefined, `${label} received`);
  const ended = await waitReceipt(seq, "ended", start + frames.length,
    `${label} ended`);
  check(!receipts.some((receipt) => receipt.seq === seq &&
    receipt.status === "rejected"), `${label}: no rejected receipt`);
  actions.push({ label, start, ended: ended.tick, frames: frames.length,
    keys: frames[0]?.keys ?? [], buttons: frames[0]?.buttons ?? [] });
  return { start, ended: ended.tick, seq };
}

function hold(keys, ticks, label) {
  return action(Array.from({ length: ticks }, () => ({ keys })), label);
}

function mouse(move, label, ticks = 1) {
  return action(Array.from({ length: ticks }, () => ({ mouse: move })), label);
}

function buttons(buttonsList, ticks, label) {
  return action(Array.from({ length: ticks }, () => ({ buttons: buttonsList })), label);
}

function combo(keys, buttonsList, label) {
  return action([{ keys, buttons: buttonsList }], label);
}

function pressKey(code, label) {
  return hold([code], 1, label);
}

function key(name) {
  const bind = keybinds?.[name];
  check(Boolean(bind) && bind.type === "keysym",
    `keybind ${name} is a keysym (${JSON.stringify(bind)})`);
  return bind.code;
}

function mouseButton(name) {
  const bind = keybinds?.[name];
  check(Boolean(bind) && bind.type === "mouse",
    `keybind ${name} is a mouse button (${JSON.stringify(bind)})`);
  return bind.code;
}

async function tp(x, y, z, yaw = 0, pitch = 0) {
  const ack = await chat(`/tp @s ${x} ${y} ${z} ${yaw} ${pitch}`);
  check(ack.ok === true, `tp ${x} ${y} ${z} accepted`);
  await waitFor(() => lastState &&
    Math.abs(lastState.x - x) < 0.35 &&
    Math.abs(lastState.y - y) < 0.35 &&
    Math.abs(lastState.z - z) < 0.35, `tp ${x} ${y} ${z} state`, 15000);
  await sleep(250);
  return lastState;
}

function distance(a, b) {
  return Math.hypot(b.x - a.x, b.z - a.z);
}

function wrapDegrees(value) {
  return ((value + 540) % 360) - 180;
}

function findSlot(inventory, item) {
  return (inventory.slots ?? []).find((slot) => slot.item === item) ?? null;
}

async function runMovement() {
  const cases = [
    ["forward", "key.forward", [0, 1]],
    ["back", "key.back", [0, -1]],
    ["left", "key.left", [1, 0]],
    ["right", "key.right", [-1, 0]],
  ];
  for (const [name, binding, expected] of cases) {
    await tp(100.5, -60, 100.5, 0, 0);
    const before = lastState;
    await hold([key(binding)], 6, `${name} 6 ticks`);
    await sleep(300);
    const after = lastState;
    const dx = after.x - before.x;
    const dz = after.z - before.z;
    const length = Math.hypot(dx, dz);
    const dot = length > 0.001 ? (dx * expected[0] + dz * expected[1]) / length : 0;
    check(length > 0.4, `${name}: moved ${length.toFixed(3)} blocks`);
    check(dot > 0.85, `${name}: direction dot ${dot.toFixed(3)}`);
  }
}

async function runJump() {
  await tp(100.5, -60, 100.5, 0, 0);
  const before = lastState;
  const started = await hold([key("key.jump")], 2, "jump 2 ticks");
  await sleep(1100);
  const samples = stateLog.filter((state) => state.tick >= started.start);
  const maxY = Math.max(before.y, ...samples.map((state) => state.y));
  check(maxY > before.y + 0.5,
    `jump rise ${(maxY - before.y).toFixed(3)} blocks`);
  check(samples.some((state) => !state.on_ground), "jump observed airborne");
  check(lastState.on_ground === true, "jump landed");
}

async function runSpeed(name, keys, compare) {
  await tp(100.5, -60, 100.5, 0, 0);
  const before = lastState;
  await hold(keys, 10, `${name} 10 ticks`);
  await sleep(300);
  const moved = distance(before, lastState);
  check(moved > 0.2, `${name}: moved ${moved.toFixed(3)} blocks`);
  if (compare) compare(moved);
  return moved;
}

async function runMovementModes() {
  const walk = await runSpeed("walk", [key("key.forward")]);
  await runSpeed("sneak", [key("key.sneak"), key("key.forward")], (moved) => {
    check(moved < walk * 0.7,
      `sneak slower than walk (${moved.toFixed(3)} vs ${walk.toFixed(3)})`);
  });
  await runSpeed("sprint", [key("key.sprint"), key("key.forward")], (moved) => {
    check(moved > walk * 1.15,
      `sprint faster than walk (${moved.toFixed(3)} vs ${walk.toFixed(3)})`);
  });
}

async function runAttack() {
  await tp(200.5, -60, 200.5, 90, 0);
  let ack = await chat("/setblock 198 -59 200 minecraft:dirt");
  check(ack.ok === true, "attack setblock accepted");
  await sleep(300);
  const before = await queryBlock([198, -59, 200]);
  check(before.block_state === "minecraft:dirt", `attack target is dirt (${before.block_state})`);
  await buttons([mouseButton("key.attack")], 20, "attack dirt 20 ticks");
  await sleep(300);
  const after = await queryBlock([198, -59, 200]);
  check(after.block_state === "minecraft:air",
    `attack broke dirt (${after.block_state})`);
}

async function runUse() {
  await tp(200.5, -60, 200.5, 90, 0);
  let ack = await chat("/setblock 198 -59 200 minecraft:note_block[note=0]");
  check(ack.ok === true, "use setblock accepted");
  await sleep(300);
  const before = await queryBlock([198, -59, 200]);
  check(before.block_state === "minecraft:note_block",
    `use target is note block (${before.block_state})`);
  check(before.properties?.note === "0", "note block starts at note 0");
  await buttons([mouseButton("key.use")], 2, "use note block");
  await sleep(300);
  const after = await queryBlock([198, -59, 200]);
  check(after.properties?.note === "1", "note block note advanced after use");
}

async function runHotbar() {
  await tp(100.5, -60, 100.5, 0, 0);
  await pressKey(key("key.hotbar.3"), "select hotbar 3");
  await sleep(250);
  check(lastState.hotbar === 2, `hotbar selection is 2 (${lastState.hotbar})`);
  let inventory = await queryInventory();
  check(inventory.ok === true && inventory.selected === 2,
    `inventory selected 2 (${inventory.selected})`);

  const before = lastState.hotbar;
  await mouse({ scroll: -1 }, "scroll hotbar");
  await sleep(250);
  check(lastState.hotbar !== before && lastState.hotbar >= 0 && lastState.hotbar <= 8,
    `scroll changed hotbar (${before} -> ${lastState.hotbar})`);

  await pressKey(key("key.hotbar.1"), "reset hotbar 1");
  await sleep(200);
}

async function runCamera() {
  await tp(100.5, -60, 100.5, 0, 0);
  const before = lastState;
  const sens = settings.sensitivity * 0.6 + 0.2;
  const mod = sens * sens * sens;
  const invertX = settings.invert_mouse_x ? -1 : 1;
  const invertY = settings.invert_mouse_y ? -1 : 1;
  const dx = 100;
  const dy = 50;
  await mouse({ dx, dy }, "camera 100/50");
  await sleep(350);
  const after = lastState;
  const yawDelta = wrapDegrees(after.yaw - before.yaw);
  const pitchDelta = after.pitch - before.pitch;
  const expectedYaw = dx * mod * 1.2 * invertX;
  const expectedPitch = dy * mod * 1.2 * invertY;
  check(Math.abs(yawDelta - expectedYaw) < 2.5,
    `camera yaw ${yawDelta.toFixed(2)} vs ${expectedYaw.toFixed(2)}`);
  check(Math.abs(pitchDelta - expectedPitch) < 2.5,
    `camera pitch ${pitchDelta.toFixed(2)} vs ${expectedPitch.toFixed(2)}`);
  check(Math.hypot(after.x - before.x, after.z - before.z) < 0.02,
    "camera did not move the player");
  await tp(100.5, -60, 100.5, 0, 0);
}

async function runInventory() {
  await tp(400.5, -60, 400.5, 0, 0);
  let ack = await chat("/clear @s");
  check(ack.ok === true, "inventory clear accepted");
  ack = await chat("/give @s minecraft:dirt 2");
  check(ack.ok === true, "inventory give dirt accepted");
  ack = await chat("/give @s minecraft:stone 3");
  check(ack.ok === true, "inventory give stone accepted");
  await sleep(450);
  await pressKey(key("key.hotbar.1"), "inventory select hotbar 1");
  await sleep(200);

  let inventory = await queryInventory();
  let dirt = findSlot(inventory, "minecraft:dirt");
  check(dirt !== null && dirt.slot === 0 && dirt.count === 2,
    `inventory dirt at 0:2 (${JSON.stringify(dirt)})`);

  await pressKey(key("key.inventory"), "open inventory");
  await waitFor(() => lastState.screen_open === true, "inventory screen open", 5000);
  check(String(lastState.screen ?? "").includes("InventoryScreen"),
    `inventory screen (${lastState.screen})`);
  await sleep(250);

  const guiScale = settings.gui_scale;
  const left = (settings.gui_width - 176) / 2;
  const top = (settings.gui_height - 166) / 2;
  const slotX = (left + 8 + 8) * guiScale;
  const slotY = (top + 142 + 8) * guiScale;

  // Clamp the virtual cursor to (0,0) while the screen is open, then move to
  // the hotbar slot 0 and shift-click it.
  await mouse({ dx: -10000, dy: -10000 }, "inventory cursor clamp");
  await sleep(250);
  await mouse({ dx: slotX, dy: slotY }, "inventory cursor to slot 0");
  await sleep(300);
  await combo([key("key.sneak")], [mouseButton("key.attack")],
    "inventory shift-click slot 0");
  await sleep(400);

  inventory = await queryInventory();
  const movedDirt = findSlot(inventory, "minecraft:dirt");
  check(movedDirt !== null && movedDirt.slot === 9 && movedDirt.count === 2,
    `shift-click moved dirt to slot 9 (${JSON.stringify(movedDirt)})`);
  check(findSlot(inventory, "minecraft:dirt")?.slot !== 0,
    "shift-click emptied slot 0");

  await pressKey(key("key.inventory"), "close inventory");
  await waitFor(() => lastState.screen_open === false, "inventory screen closed", 5000);

  ack = await chat("/clear @s");
  check(ack.ok === true, "inventory clear for drop accepted");
  ack = await chat("/give @s minecraft:dirt 2");
  check(ack.ok === true, "inventory give dirt for drop accepted");
  await sleep(350);
  await pressKey(key("key.hotbar.1"), "select hotbar 1 for drop");
  await sleep(200);
  inventory = await queryInventory();
  const beforeDrop = findSlot(inventory, "minecraft:dirt");
  check(beforeDrop !== null && beforeDrop.count === 2,
    `drop starts at 2 (${JSON.stringify(beforeDrop)})`);
  await pressKey(key("key.drop"), "drop one dirt");
  await sleep(300);
  inventory = await queryInventory();
  const afterDrop = findSlot(inventory, "minecraft:dirt");
  check(afterDrop !== null && afterDrop.count === 1,
    `drop leaves one (${JSON.stringify(afterDrop)})`);
}

async function runMinimizedSubset() {
  await tp(100.5, -60, 100.5, 0, 0);
  const before = lastState;
  await hold([key("key.forward")], 6, "minimized forward");
  await sleep(300);
  check(distance(before, lastState) > 0.4,
    `minimized forward moved ${distance(before, lastState).toFixed(3)}`);

  await runJump();
  await runAttack();
  await runUse();
  await runCamera();
  await runHotbar();

  await tp(400.5, -60, 400.5, 0, 0);
  await pressKey(key("key.inventory"), "minimized open inventory");
  await waitFor(() => lastState.screen_open === true, "minimized inventory open", 5000);
  await pressKey(key("key.inventory"), "minimized close inventory");
  await waitFor(() => lastState.screen_open === false, "minimized inventory closed", 5000);
}

async function run() {
  await waitFor(() => session, "SessionInfo", 30000);
  check((session.image?.width ?? 0) > 0 && (session.image?.height ?? 0) > 0,
    "image spec");
  check(session.render?.width === session.image?.width &&
    session.render?.height === session.image?.height &&
    session.window?.width === session.image?.width &&
    session.window?.height === session.image?.height,
    "render/window match image size");
  for (const capability of ["raw_input", "query_block", "query_inventory",
    "query_settings", "set_hud", "chat"]) {
    check((session.capabilities ?? []).includes(capability),
      `capability ${capability}`);
  }

  const stable = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.on_ground === true && lastState.capture?.valid === true &&
      lastState.tick > 10 && lastState.input_locked === true ? lastState : null),
    "stable locked state", 30000);
  check(stable.input_locked === true, "input lock armed");

  settings = await querySettings();
  check(settings.ok === true && settings.reason === null, "settings query ok");
  check(settings.smooth_camera === false, "smooth camera is disabled");
  check(settings.scoping === false, "not scoping");
  keybinds = settings.keybinds;
  check(keybinds && typeof keybinds === "object" && !Array.isArray(keybinds),
    "keybind table present");
  check(Object.keys(keybinds).length >= 40,
    `keybind table size ${Object.keys(keybinds).length}`);
  for (const name of ["key.forward", "key.back", "key.left", "key.right",
    "key.jump", "key.sneak", "key.sprint", "key.attack", "key.use",
    "key.drop", "key.inventory", "key.hotbar.1"]) {
    check(Boolean(keybinds[name]), `keybind ${name} present`);
  }

  let ack = await chat("/gamemode survival");
  check(ack.ok === true && ack.kind === "command", "gamemode survival accepted");
  await tp(100.5, -60, 100.5, 0, 0);

  if (minimized) {
    await runMinimizedSubset();
  } else {
    await runMovement();
    await runJump();
    await runMovementModes();
    await runAttack();
    await runUse();
    await runHotbar();
    await runCamera();
    await runInventory();
  }

  return {
    minimized,
    settings,
    keybindCount: Object.keys(keybinds).length,
    checks,
    actions,
  };
}

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(overallTimer);
  if (error) {
    failedReason = error.message;
    console.error(`[matrix] fail: ${failedReason}`);
  }
  const report = {
    session,
    minimized,
    settings,
    keybindCount: keybinds ? Object.keys(keybinds).length : 0,
    checks,
    actions,
    receipts,
    failedReason,
    passed: !failedReason,
  };
  fs.writeFileSync(path.join(outDir, "report.json"),
    JSON.stringify(report, null, 2));
  console.log(`[matrix] checks=${checks.length} actions=${actions.length} ` +
    `minimized=${minimized} passed=${!failedReason}`);

  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "matrix-check" });
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

function closeAndExit() {
  clearTimeout(overallTimer);
  if (socketRef) socketRef.destroy();
  server.close(() => process.exit(failedReason ? 2 : 0));
}

overallTimer = setTimeout(() => {
  finish(new Error(`timeout after ${timeoutMs} ms; state=${lastState?.tick ?? "-"}`));
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[matrix] listening on 127.0.0.1:${port}, out=${outDir}, ` +
    `minimized=${minimized}`);
});

run()
  .then(() => finish(null))
  .catch((error) => finish(error));
