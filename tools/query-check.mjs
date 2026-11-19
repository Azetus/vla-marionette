#!/usr/bin/env node
/**
 * Query/chat capability check host.
 *
 * One session: verify the new capabilities, exercise Chat (command + plain
 * chat), QueryInventory, QueryBlock (position and crosshair), and negative
 * shapes. No ActionChunk is sent, so the player must not move.
 *
 * Usage:
 *   node tools/query-check.mjs --port 25580 --out runs/query-check [--shutdown]
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
const outDir = path.resolve(arg("out", "runs/query-check"));
const shutdown = process.argv.includes("--shutdown");

fs.mkdirSync(outDir, { recursive: true });

let socketRef = null;
let buffer = Buffer.alloc(0);
let session = null;
let lastState = null;
let finished = false;
let failedReason = null;
let shutdownTimer;
let requestCounter = 100;

const pending = new Map();
const unmatched = [];
const responses = [];
const checks = [];
let before = null;
let after = null;

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
  socket.on("error", (error) => finish(error));
});

function handleMessage(header) {
  switch (header.type) {
    case "SessionInfo":
      session = header;
      break;
    case "State":
      lastState = header;
      break;
    case "QueryBlockResult":
    case "QueryInventoryResult":
    case "QuerySettingsResult":
    case "ChatAck": {
      responses.push(header);
      const id = header.request_id;
      if (id !== null && pending.has(id)) {
        const resolve = pending.get(id);
        pending.delete(id);
        resolve(header);
      } else {
        unmatched.push(header);
      }
      break;
    }
    case "ShutdownAck":
      console.log(`[query] ShutdownAck save=${header.save} reason=${header.reason}`);
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

function chat(text) {
  const id = nextId();
  return sendRequest({ type: "Chat", request_id: id, text });
}

function queryBlock(pos = null) {
  const id = nextId();
  const header = { type: "QueryBlock", request_id: id };
  if (pos) header.pos = pos;
  return sendRequest(header);
}

function queryInventory() {
  const id = nextId();
  return sendRequest({ type: "QueryInventory", request_id: id });
}

function querySettings() {
  const id = nextId();
  return sendRequest({ type: "QuerySettings", request_id: id });
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

async function waitForUnmatched(type, waitMs = 5000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const index = unmatched.findIndex((message) => message.type === type);
    if (index >= 0) {
      return unmatched.splice(index, 1)[0];
    }
    await sleep(50);
  }
  throw new Error(`no uncorrelated ${type} response`);
}

function check(condition, label) {
  if (!condition) throw new Error(label);
  checks.push(label);
}

async function run() {
  await waitFor(() => session, "SessionInfo");
  check(session.image?.width === 854 && session.image?.height === 480 &&
    session.image?.pixel_format === "jpeg", "image spec 854x480 jpeg");
  check(session.render?.width === 640 && session.render?.height === 360,
    "render target 640x360");
  for (const capability of ["query_block", "query_inventory", "chat", "query_settings"]) {
    check((session.capabilities ?? []).includes(capability), `capability ${capability}`);
  }

  const state0 = await waitFor(
    () => (lastState && lastState.in_world && lastState.screen_open === false &&
      lastState.tick > 10 && lastState.input_locked === true ? lastState : null),
    "stable in-world state");
  check(state0.on_ground === true, "player is on ground");
  check(state0.input_locked === true, "physical input lock is armed");

  const settings = await querySettings();
  check(settings.ok === true && settings.reason === null, "settings query ok");
  check(["survival", "creative", "adventure", "spectator"].includes(settings.gamemode),
    `settings gamemode: ${settings.gamemode}`);
  check(typeof settings.sensitivity === "number" &&
    settings.sensitivity >= 0 && settings.sensitivity <= 1,
    `settings sensitivity: ${settings.sensitivity}`);
  check(typeof settings.invert_mouse_x === "boolean" &&
    typeof settings.invert_mouse_y === "boolean", "settings invert flags");
  check(typeof settings.smooth_camera === "boolean" &&
    typeof settings.scoping === "boolean", "settings camera flags");
  check(["first_person", "third_person_back", "third_person_front"]
    .includes(settings.camera_type), `settings camera_type: ${settings.camera_type}`);
  check(Number.isInteger(settings.gui_scale) && settings.gui_scale >= 1,
    `settings gui_scale: ${settings.gui_scale}`);
  check(Number.isInteger(settings.gui_width) && settings.gui_width > 0 &&
    Number.isInteger(settings.gui_height) && settings.gui_height > 0,
    `settings gui size: ${settings.gui_width}x${settings.gui_height}`);
  const keybinds = settings.keybinds;
  check(keybinds && typeof keybinds === "object" && !Array.isArray(keybinds),
    "settings keybinds object");
  check(Object.keys(keybinds).length >= 40,
    `settings keybind count: ${Object.keys(keybinds).length}`);
  check(keybinds["key.forward"]?.type === "keysym" &&
    keybinds["key.forward"]?.code === 87, "key.forward is W (87)");
  check(keybinds["key.attack"]?.type === "mouse" &&
    keybinds["key.attack"]?.code === 0, "key.attack is mouse 0");
  check(keybinds["key.use"]?.type === "mouse" &&
    keybinds["key.use"]?.code === 1, "key.use is mouse 1");
  check(keybinds["key.hotbar.1"]?.type === "keysym" &&
    keybinds["key.hotbar.1"]?.code === 49, "key.hotbar.1 is 1 (49)");
  check(keybinds["key.drop"]?.type === "keysym" &&
    keybinds["key.drop"]?.code === 81, "key.drop is Q (81)");
  check(keybinds["key.smoothCamera"] === null, "unbound mapping is null");

  const bx = Math.floor(state0.x);
  const by = Math.floor(state0.y);
  const bz = Math.floor(state0.z);
  const target = [bx, by + 5, bz];
  before = { x: state0.x, y: state0.y, z: state0.z, tick: state0.tick };

  // Inventory: clear, then give a known stack.
  let ack = await chat("/clear @s");
  check(ack.ok === true && ack.kind === "command" && ack.reason === null,
    "clear command accepted");
  await sleep(300);

  let inventory = await queryInventory();
  check(inventory.ok === true && inventory.reason === null, "inventory query ok");
  check(Number.isInteger(inventory.selected) && inventory.selected >= 0 &&
    inventory.selected <= 8, "inventory selected slot in 0..8");
  check(Array.isArray(inventory.slots), "inventory slots array");
  check(inventory.slots.length === 0, "inventory empty after clear");

  ack = await chat("/give @s minecraft:stone 5");
  check(ack.ok === true && ack.kind === "command", "give command accepted");
  await sleep(400);

  inventory = await queryInventory();
  check(inventory.ok === true, "inventory query after give ok");
  const stone = inventory.slots.find((slot) => slot.item === "minecraft:stone");
  check(Boolean(stone), "given stone is visible");
  check(stone.count === 5, "given stone count is 5");
  check(stone.slot >= 0 && stone.slot <= 8, "given stone is in the hotbar");
  check(inventory.selected === 0, "selected slot is 0");

  // Block reads: air, then a block with properties, then a block entity.
  // Reset the target first so repeated runs do not inherit an earlier block.
  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} air`);
  check(ack.ok === true && ack.kind === "command", "setblock air accepted");
  await sleep(300);

  let block = await queryBlock(target);
  check(block.ok === true && block.query === "pos", "position block query ok");
  check(block.block_state === "minecraft:air", "target block is air");
  check(Object.keys(block.properties ?? {}).length === 0, "air has no properties");
  check(block.block_entity === null, "air has no block entity");

  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} minecraft:oak_log`);
  check(ack.ok === true && ack.kind === "command", "setblock oak_log accepted");
  await sleep(400);

  block = await queryBlock(target);
  check(block.ok === true && block.block_state === "minecraft:oak_log",
    "oak_log state read back");
  check(block.properties?.axis === "y", "oak_log axis property");
  check(block.block_entity === null, "oak_log has no block entity");

  ack = await chat(`/setblock ${target[0]} ${target[1]} ${target[2]} minecraft:chest`);
  check(ack.ok === true, "setblock chest accepted");
  await sleep(400);

  block = await queryBlock(target);
  check(block.ok === true && block.block_entity === "minecraft:chest",
    "chest block entity read back");

  // Bounds and loaded-chunk errors.
  let error = await queryBlock([bx, 400, bz]);
  check(error.ok === false && error.reason === "out_of_bounds", "y=400 is out_of_bounds");
  error = await queryBlock([bx + 10000, by, bz]);
  check(error.ok === false && error.reason === "chunk_not_loaded",
    "far x is chunk_not_loaded");

  // Shape errors.
  const badPosId = nextId();
  error = await sendRequest({ type: "QueryBlock", request_id: badPosId, pos: [1, 2] });
  check(error.ok === false && error.reason === "bad_shape" &&
    error.request_id === badPosId, "short pos is bad_shape with request id");

  const badTextId = nextId();
  let badText = await sendRequest({ type: "Chat", request_id: badTextId, text: "" });
  check(badText.ok === false && badText.reason === "bad_text" &&
    badText.kind === null, "empty chat is bad_text");

  sendFrame({ type: "QueryBlock" });
  const uncorrelated = await waitForUnmatched("QueryBlockResult");
  check(uncorrelated.request_id === null && uncorrelated.ok === false &&
    uncorrelated.reason === "bad_shape", "missing request_id is uncorrelated bad_shape");

  // Plain chat path (no leading slash).
  ack = await chat("vla-query-check chat path");
  check(ack.ok === true && ack.kind === "chat" && ack.reason === null,
    "plain chat accepted");

  // No ActionChunk was sent, so the player must not have moved.
  const stateAfterQueries = await waitFor(
    () => (lastState && lastState.tick > state0.tick + 5 ? lastState : null),
    "state after queries");
  const moved = Math.hypot(stateAfterQueries.x - before.x, stateAfterQueries.z - before.z);
  check(moved <= 0.05, "player did not move during queries");
  check(stateAfterQueries.input_locked === true, "input lock still armed");
  after = { x: stateAfterQueries.x, y: stateAfterQueries.y, z: stateAfterQueries.z,
    tick: stateAfterQueries.tick };

  // Crosshair query: face west, place a block two blocks in front.
  ack = await chat(`/tp @s ${state0.x} ${state0.y} ${state0.z} 90 0`);
  check(ack.ok === true && ack.kind === "command", "tp with rotation accepted");
  await waitFor(() => (lastState && Math.abs(lastState.yaw - 90) < 1 &&
    Math.abs(lastState.pitch) < 1 ? lastState : null), "yaw 90 / pitch 0");
  await sleep(400);

  const crossX = bx - 3;
  const crossY = by + 1;
  const crossZ = bz;
  // Clear the eye-level corridor so leftover blocks from earlier runs cannot
  // intercept the crosshair ray before the target block.
  ack = await chat(`/fill ${bx - 4} ${by + 1} ${bz} ${bx - 1} ${by + 1} ${bz} air`);
  check(ack.ok === true && ack.kind === "command", "clear crosshair corridor accepted");
  await sleep(300);
  ack = await chat(`/setblock ${crossX} ${crossY} ${crossZ} minecraft:stone`);
  check(ack.ok === true, "setblock crosshair target accepted");
  await sleep(500);

  const crosshair = await queryBlock();
  check(crosshair.ok === true, `crosshair query ok: ${JSON.stringify(crosshair)}`);
  check(crosshair.query === "crosshair", "crosshair query mode");
  check(Array.isArray(crosshair.pos) && crosshair.pos[0] === crossX &&
    crosshair.pos[1] === crossY && crosshair.pos[2] === crossZ,
    `crosshair hit expected block: ${JSON.stringify(crosshair.pos)}`);
  check(crosshair.face === "east", `crosshair face east: ${crosshair.face}`);
  check(crosshair.distance >= 1.5 && crosshair.distance <= 3.5,
    `crosshair distance plausible: ${crosshair.distance}`);

  // The inventory survived all reads and commands.
  inventory = await queryInventory();
  check(inventory.slots.some((slot) => slot.item === "minecraft:stone" &&
    slot.count === 5), "inventory still holds 5 stone");
}

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  if (error) {
    failedReason = error.message;
    console.error(`[query] fail: ${failedReason}`);
  }
  const report = {
    session,
    before,
    after,
    checks,
    responses,
    failedReason,
    shutdownRequested: shutdown,
    passed: !failedReason,
  };
  fs.writeFileSync(path.join(outDir, "report.json"), JSON.stringify(report, null, 2));
  console.log(`[query] checks=${checks.length} responses=${responses.length} passed=${!failedReason}`);
  if (!failedReason && shutdown && socketRef && !socketRef.destroyed) {
    sendFrame({ type: "Shutdown", save: true, reason: "query-check" });
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
  finish(new Error(`timeout after ${timeoutMs} ms; state=${lastState?.tick ?? "-"} responses=${responses.length}`));
}, timeoutMs);

server.listen(port, "127.0.0.1", () => {
  console.log(`[query] listening on 127.0.0.1:${port}, out=${outDir}`);
});

run().then(() => finish(null)).catch((error) => finish(error));

