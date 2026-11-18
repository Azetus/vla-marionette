# Protocol

This document describes the wire protocol implemented by the mod. It is a
device-layer protocol: the mod captures frames, reports state, injects raw input,
and returns receipts. It does not interpret model output.

The protocol has **no global version field**. Compatibility is expressed by the
fields in `SessionInfo` and by the `capabilities` array. A host must only use
capabilities it understands; unknown optional fields are ignored.

## 1. Transport

### 1.1 Framing

Big-endian:

```
u32 frameLen      # bytes after this field: 4 + headerLen + bodyLen
u32 headerLen
header            # UTF-8 JSON object; must contain "type"
body              # frameLen - 4 - headerLen bytes; JPEG or empty
```

Limits:

- `headerLen <= 64 KiB`
- `bodyLen <= 16 MiB`
- invalid lengths or invalid JSON close the connection.

The mod dials the host over TCP. Each successful connection is a new session.
TCP_NODELAY is enabled.

### 1.2 Ordering

- Handshake (`SessionInfo`) is the first outbound message.
- Control messages (state, receipts, Pong, ShutdownAck) have priority over
  frames.
- Frames use one latest-wins slot; a slow host loses old frames, never the
  newest.
- Control queue exhaustion that would prevent receipt delivery closes the
  session.

## 2. Messages

| Direction | Type | Purpose |
|---|---|---|
| mod → host | `SessionInfo` | handshake and capabilities |
| mod → host | `Observation` | one JPEG frame |
| mod → host | `State` | periodic state snapshot |
| mod → host | `ActionReceipt` | action lifecycle |
| mod → host | `Pong` | reply to Ping |
| mod → host | `ShutdownAck` | shutdown request accepted |
| mod → host | `QueryBlockResult` | async reply to `QueryBlock` |
| mod → host | `QueryInventoryResult` | async reply to `QueryInventory` |
| mod → host | `ChatAck` | async reply to `Chat` |
| host → mod | `ActionChunk` | ordered raw input frames |
| host → mod | `Release` | interrupt current input |
| host → mod | `Ping` | optional liveness probe |
| host → mod | `Shutdown` | save and close the client |
| host → mod | `QueryBlock` | read a block state / block entity |
| host → mod | `QueryInventory` | read the selected slot and inventory |
| host → mod | `Chat` | send one chat line, command when it starts with `/` |

## 3. `SessionInfo`

```json
{
  "type": "SessionInfo",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "mc_version": "26.1.2",
  "mod_version": "0.1.0",
  "tick_ms": 50,
  "image": {
    "width": 854,
    "height": 480,
    "pixel_format": "jpeg",
    "origin": "top-left"
  },
  "render": {"width": 640, "height": 360},
  "window": {"width": 640, "height": 360},
  "capabilities": ["ping", "observation", "state", "raw_input", "shutdown",
                   "query_block", "query_inventory", "chat"],
  "limits": {"max_action_frames": 20}
}
```

| Field | Type | Meaning |
|---|---|---|
| `type` | string | `SessionInfo` |
| `session_id` | string | new UUID per TCP connection |
| `mc_version` | string | Minecraft version |
| `mod_version` | string | mod version |
| `tick_ms` | int | nominal logical tick, 50 ms |
| `image` | object | JPEG output specification |
| `render` | object | render target size |
| `window` | object | client window size |
| `capabilities` | string[] | implemented features only |
| `limits.max_action_frames` | int | maximum frames in one `ActionChunk` |

Current capabilities:

- `ping`
- `observation`
- `state`
- `raw_input`
- `shutdown`
- `query_block`
- `query_inventory`
- `chat`

`tick_ms` is a nominal value. The logical tick can catch up or pause; it is not a
wall-clock guarantee.

## 4. `Observation`

```json
{
  "type": "Observation",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "obs_id": 17,
  "state_tick": 123,
  "submit_ns": 456789012345,
  "img_w": 854,
  "img_h": 480,
  "pixel_format": "jpeg"
}
```

The body is a JPEG. Current implementation:

- render target 640×360;
- encoded 854×480, quality 0.9;
- wall-clock 5 Hz target;
- HUD is kept;
- `state_tick` is the logical tick at sampling time;
- `obs_id` increases per session;
- `submit_ns` is a local monotonic timestamp for diagnostics.

## 5. `State`

Sent at a wall-clock 10 Hz target, driven by client ticks and render frames. It
is sent even when no world/player exists.

```json
{
  "type": "State",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "tick": 123,
  "game_time": 456,
  "in_world": true,
  "dimension": "minecraft:overworld",
  "x": 1.5,
  "y": 64.0,
  "z": -3.25,
  "yaw": 90.0,
  "pitch": 12.5,
  "health": 20.0,
  "alive": true,
  "on_ground": true,
  "hotbar": 0,
  "screen": null,
  "screen_open": false,
  "input_locked": true,
  "held": {"keys": [], "buttons": []},
  "capture": {
    "valid": true,
    "reason": null,
    "last_obs_id": 17,
    "last_frame_tick": 122,
    "encode_failures": 0
  }
}
```

| Field | Meaning |
|---|---|
| `tick` | logical client tick |
| `game_time` | level game time; null outside a world |
| `in_world` | level is present |
| `dimension` | dimension id; null outside a world |
| `x/y/z` | player position rounded to 0.001; null without a player |
| `yaw/pitch` | player rotation rounded to 0.001 |
| `health` | player health |
| `alive` | player alive |
| `on_ground` | player on ground |
| `hotbar` | selected slot 0–8 |
| `screen` | simple class name of the current `Screen`, or null |
| `screen_open` | whether a `Screen` is open |
| `input_locked` | physical input is currently suppressed |
| `held` | actual raw held state: `keys`/`buttons` arrays |
| `held.keys` | GLFW keyboard codes currently held |
| `held.buttons` | GLFW mouse button codes currently held |
| `capture` | capture status block |

`capture.reason` is null when `valid` is true. Invalid examples include
`no_level`, `bad_size`, `no_target`, and `not_ready`.

## 6. `ActionChunk`

One chunk is an ordered list of raw input frames, one frame per logical client
tick. `frames.length` is the duration; there is no separate `ticks` field.

```json
{
  "type": "ActionChunk",
  "seq": 42,
  "start_tick": 320,
  "frames": [
    {"keys": [69]},
    null,
    {"keys": [340], "buttons": [0], "mouse": {"dx": 125, "dy": -50, "scroll": 0}}
  ]
}
```

| Field | Constraints |
|---|---|
| `seq` | integer ≥ 0; unique among accepted chunks in a session |
| `start_tick` | integer ≥ 0; must be greater than the current tick at acceptance |
| `frames` | array of 1–20 objects or `null` |
| `frames[i].keys` | GLFW keyboard codes; optional; empty set if absent |
| `frames[i].buttons` | GLFW mouse button codes; optional; empty set if absent |
| `frames[i].mouse` | optional object |
| `frames[i].mouse.dx/dy` | client-area logical pixels; optional, default 0 |
| `frames[i].mouse.scroll` | scroll notches; optional, default 0 |
| `session_id` | optional; if present must match the connection |

Input code ranges:

- keyboard: GLFW key codes 32–348;
- mouse buttons: 0–7.

A `null` frame is equivalent to `{}`: no keys, no buttons, no mouse movement.
Unknown optional fields are ignored. Unknown or out-of-range input codes are
rejected with `bad_input`.

### 6.1 Tick definition

A tick is one Minecraft **client logical tick**: one `Minecraft.tick()` execution,
observed at `ClientTickEvent.Pre` through `Post`. It is nominally 20 TPS, but it
is not a wall-clock 50 ms and not a render frame. `start_tick` uses this counter.

### 6.2 Single-tick limitation

A frame stores one held state per tick. Consequently, a chunk cannot represent
multiple press/release edges of the same key or button within one client tick.
For example, “press E → release E → press E” inside one tick cannot be encoded.
This is a deliberate limitation; a future sub-tick event stream would be needed
for that level of detail.

## 7. `ActionReceipt`

```json
{
  "type": "ActionReceipt",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "seq": 42,
  "status": "applied",
  "tick": 320
}
```

| Status | Meaning | `tick` |
|---|---|---|
| `received` | accepted by the scheduler | absent |
| `applied` | first frame is being applied | `start_tick` |
| `ended` | last frame and mouse flush delivered | `start_tick + frames.length` |
| `rejected` | not accepted | absent |
| `interrupted` | accepted, then stopped | current tick |

`received` is queued inside the scheduler acceptance lock, so it cannot be
overtaken by `applied`.

### 7.1 Rejection reasons

| Reason | Meaning |
|---|---|
| `bad_shape` | missing/invalid required field |
| `bad_frames` | empty frames array or more than 20 frames |
| `bad_input` | unknown/out-of-range GLFW code or non-finite mouse value |
| `late` | `start_tick <= current tick` |
| `busy` | active or scheduled chunk already exists |
| `duplicate` | accepted `seq` repeated in this session |
| `manual_takeover` | physical ESC opened the pause screen and stopped automation |

### 7.2 Interruption reasons

| Reason | Trigger |
|---|---|
| `release` | host sent `Release` |
| `disconnected` | TCP connection ended |
| `no_level` | client left the world |
| `manual_takeover` | physical ESC entered manual takeover |

Opening a GUI does not interrupt raw input.

## 8. `Release`

```json
{"type":"Release"}
```

- Clears pending/active input and releases all keys/buttons on the main thread.
- Emits `interrupted/reason=release` for an active or scheduled chunk.
- No receipt if nothing was active.
- The connection remains open; a new chunk may use a new `seq`.

## 9. `Shutdown` / `ShutdownAck`

```json
{"type":"Shutdown","save":true,"reason":"host"}
{"type":"ShutdownAck","save":true,"reason":"host"}
```

- `save` defaults to true.
- The mod acknowledges, then saves the integrated server via `halt(true)`, then
  asks the client window to close.
- Window close goes through Minecraft's normal destroy/save path.
- Without an integrated server, only the window close is requested.

## 10. `Ping` / `Pong`

```json
{"type":"Ping","nonce":"abc123"}
{"type":"Pong","nonce":"abc123"}
```

`nonce` is echoed when present. Ping does not require Minecraft state.

## 11. Scheduling

- One active chunk + at most one scheduled chunk.
- `start_tick <= current tick` is rejected as `late`; it is not shifted.
- `seq` is recorded only when accepted. A rejected chunk does not consume its
  `seq`.
- `applied` is sent at the first frame's `ClientTickEvent.Pre`.
- `ended` is sent at the last frame's `ClientTickEvent.Post`, after the mouse
  flush.
- If no next chunk follows, the next tick acts as an empty frame and releases
  held input.
- Adjacent chunks preserve held state for the same key/button; there is no
  forced release at a chunk boundary.

## 12. Input injection

- Keyboard: vanilla `KeyboardHandler.keyPress`/`keyRelease`; with a `Screen`, the
  screen receives the key; otherwise `KeyMapping` does.
- Mouse buttons: vanilla `MouseHandler.onButton`; GUI receives
  `mouseClicked`/`mouseReleased`, the world receives `KeyMapping` + click.
- Mouse movement: a virtual cursor is advanced by raw pixels and flushed through
  `MouseHandler.handleAccumulatedMovement()` at tick post.
- Scroll: vanilla `MouseHandler.onScroll`.
- Injected events bypass the focus check while the injection marker is active;
  they are not interpreted as GUI-specific actions.
- GUI/world semantics are decided entirely by vanilla `Screen`/`KeyMapping`.

### 12.1 Physical input lock and ESC takeover

- A connected session arms the physical input lock.
- Physical keyboard/mouse events are dropped while the lock is armed.
- Injected events carry `InputInjection` and always pass.
- Physical ESC is always allowed. From the world (no `Screen`), it:
  1. releases injected keys/buttons;
  2. interrupts active/scheduled input with `manual_takeover`;
  3. opens the pause screen;
  4. allows physical input while the pause screen is open.
- When the pause screen closes, the lock re-arms. The host must send a new
  `ActionChunk` to continue.
- While manual takeover is active, new `ActionChunk` messages are rejected with
  `manual_takeover`.
- `State.input_locked` is true while physical input is suppressed; false during
  pause takeover and after disconnect.
- `State.held` reports the actual raw held keys/buttons, including injected
  input and physical input that is currently allowed.

## 13. Failure and cleanup

- Invalid framing/JSON: close the connection.
- Invalid action shape: `rejected`, keep the connection.
- Invalid query/chat shape: `ok:false` response with a reason, keep the connection.
- Control queue cannot deliver receipts: close the session and release input.
- Disconnect/release/leave world: release keys, buttons, and pending mouse.
- Normal client shutdown releases input on a best-effort basis.

## 14. Minimal M1 sequence

```
mod                          host
 |---- SessionInfo ---------->|
 |<--- Ping ------------------|
 |---- Pong ----------------->|
 |---- State (tick=T) ------->|
 |---- Observation ---------->|
 |<--- ActionChunk -----------|  frames = N, keys = W
 |---- ActionReceipt(received)|
 |---- ActionReceipt(applied) |  tick=T0
 |---- ActionReceipt(ended) --|  tick=T0+N
 |---- State (tick>=ended) -->|
 |---- Observation ---------->|  post-action frame
 |<--- Release ---------------|
```

The acceptance harness asserts the action-end state, a post-action JPEG, and a
new session after reconnect.

## 15. Queries and chat: common rules

`QueryBlock`, `QueryInventory` and `Chat` are request/response messages keyed by
`request_id`.

- `request_id` is a required integer. It is opaque, echoed unchanged, and not
  deduplicated: two requests may share an id.
- Shape validation runs on the network thread. Block/inventory reads and chat
  dispatch run on the client thread through `Minecraft.execute`.
- Responses are control messages, not frames. They may interleave with `State`,
  receipts, or each other; **responses may arrive out of order** and must be
  correlated by `request_id`.
- A response is only delivered while the request's session is still the current
  connection. A delayed response from an old session is dropped.
- At most 32 requests may be outstanding. Further requests are answered with
  `busy` without scheduling a client-thread task.
- Queries and chat do not touch the input lock, do not interrupt an active
  action, and do not require `screen == null`.
- Responses can be lost if the connection closes or the client is shutting down.
- Every response has `type`, `request_id` (may be `null` when the request was
  uncorrelatable), `ok`, `reason` (`null` on success), and `tick` (the client
  tick when the response was produced).

### 15.1 Failure reasons

| Reason | Meaning |
|---|---|
| `bad_shape` | required field missing or of the wrong type |
| `no_level` | no world or no local player |
| `no_target` | crosshair is not on a block |
| `chunk_not_loaded` | the requested chunk is not loaded on the client |
| `out_of_bounds` | position outside the world build bounds |
| `not_connected` | no `ClientPacketListener` |
| `bad_text` | empty/oversized text, control characters, empty command |
| `busy` | more than 32 outstanding requests |
| `not_ready` | `Minecraft` is not constructed yet |
| `internal` | unexpected failure; logged |

## 16. `QueryBlock`

Position query:

```json
{"type": "QueryBlock", "request_id": 1, "pos": [10, 64, -3]}
```

Crosshair query (omit `pos` or send `null`):

```json
{"type": "QueryBlock", "request_id": 2}
```

Success (position):

```json
{
  "type": "QueryBlockResult",
  "request_id": 1,
  "ok": true,
  "reason": null,
  "tick": 1288,
  "query": "pos",
  "pos": [10, 64, -3],
  "block_state": "minecraft:oak_log",
  "properties": {"axis": "y"},
  "block_entity": null
}
```

Success (crosshair):

```json
{
  "type": "QueryBlockResult",
  "request_id": 2,
  "ok": true,
  "reason": null,
  "tick": 1290,
  "query": "crosshair",
  "pos": [1, -59, 0],
  "block_state": "minecraft:stone",
  "properties": {},
  "block_entity": null,
  "distance": 2.515,
  "face": "east"
}
```

| Field | Meaning |
|---|---|
| `pos` (request) | optional `[x,y,z]` integers; absent/null means crosshair |
| `query` | `pos` or `crosshair` on success; absent on failure |
| `pos` (response) | the block that was read or hit |
| `block_state` | block id, e.g. `minecraft:stone` |
| `properties` | block-state property names to string values; `{}` if none |
| `block_entity` | block-entity type id, or `null`; always present |
| `distance` | crosshair only: camera-eye distance to the hit point, 3 decimals |
| `face` | crosshair only: hit face name |

Implementation notes:

- A position query checks `isInValidBounds` (`out_of_bounds`) and then asks the
  client chunk cache with `loadOrGenerate=false` (`chunk_not_loaded`).
  `ClientLevel.hasChunk` always returns true and cannot be used for this.
- A crosshair query reads vanilla `Minecraft.hitResult`, which is updated once
  per rendered frame. It is not an immediate raycast and can be up to one frame
  stale (about 100 ms at the minimized 10 FPS). A miss or an entity hit is
  `no_target`.
- Only the block id, properties and block-entity type are returned; no NBT.

## 17. `QueryInventory`

```json
{"type": "QueryInventory", "request_id": 4}
```

```json
{
  "type": "QueryInventoryResult",
  "request_id": 4,
  "ok": true,
  "reason": null,
  "tick": 1300,
  "selected": 0,
  "slots": [
    {"slot": 0, "item": "minecraft:stone", "count": 5},
    {"slot": 40, "item": "minecraft:shield", "count": 1}
  ]
}
```

- `selected` is the selected hotbar slot, 0–8.
- `slots` contains non-empty slots only, sorted by slot. A missing slot is empty.
- Slot indices follow the client inventory container: 0–8 hotbar, 9–35
  backpack, 36–39 armor (feet, legs, chest, head), 40 offhand, 41 body,
  42 saddle.
- The response reflects the client's synchronized inventory, not an
  authoritative server snapshot. A command that changes the inventory is
  visible only after the client receives the update.
- No NBT or item components are returned.

## 18. `Chat`

One chat line. A leading `/` selects the vanilla command path; otherwise the
line is sent as a chat message.

```json
{"type": "Chat", "request_id": 6, "text": "/setblock 10 64 -3 minecraft:stone"}
```

```json
{"type": "ChatAck", "request_id": 6, "ok": true, "reason": null,
 "kind": "command", "tick": 1305}
```

```json
{"type": "ChatAck", "request_id": 6, "ok": false, "reason": "bad_text",
 "kind": null, "tick": 1305}
```

| Field | Meaning |
|---|---|
| `text` | required string; at most 256 characters before trimming, non-empty after trimming |
| `kind` | `command` when the trimmed text starts with `/`, else `chat`; `null` when the text could not be parsed |

Rules:

- The text is trimmed at both ends only; internal whitespace is preserved (the
  mod does not run vanilla `ChatScreen` whitespace normalization, so command
  arguments such as quoted JSON keep their spaces).
- Control characters (`< 0x20`, `0x7F`) are rejected. A lone `/` or an empty
  command body is `bad_text`.
- Exactly one leading `/` is removed before `ClientPacketListener.sendCommand`.
  `//foo` is treated as the command `/foo`; the server reports the parse error.
- The mod does not parse command names and keeps no allowlist or denylist.
  `stop`, `kick`, `ban` and similar commands reach the server directly. Use
  `Shutdown(save=true)` for a normal close.
- The client chat screen is not opened; an action in progress is not
  interrupted and input state does not change.
- `ok=true` means the line was handed to `ClientPacketListener.sendCommand` or
  `sendChat`. It does **not** mean the server executed it: missing cheats,
  missing permissions and syntax errors are reported only in the game chat.
  Verify effects with `QueryBlock`, `QueryInventory`, or `State`.
- Plain chat is public in multiplayer and is subject to chat signing, server
  filters and rate limits. It is only as safe as the host that sends it.
