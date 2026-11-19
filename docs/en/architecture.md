# Architecture

VLA-Marionette is a client-side device layer. Its job is to turn the running
Minecraft client into a controllable observation/actuation endpoint without
embedding a policy or model-specific knowledge.

## Runtime shape

```
TCP host
   │  SessionInfo / Observation / State / ActionReceipt
   │  ActionChunk / Release / Ping / Shutdown
   ▼
Transport (connect/read/write daemon threads)
   │
   ├── InboundRouter ──► ActionScheduler ──► InputSink
   │                                      └─► McInputInjector
   │                └──► QueryService ──► client-thread readers
   │                                     └─► Chat dispatch
   │
   ├── CaptureScheduler ──► FrameMailbox ──► JPEG encoder
   │
   └── StateReporter ──► State

Minecraft main/client thread:
  ClientTickEvent.Pre/Post, RenderFrameEvent.Post, window, player, Screen
```

## Modules

### `protocol`

- `Framing`: big-endian `u32 frameLen`, `u32 headerLen`, UTF-8 JSON header,
  optional binary body. Header and body have hard limits.
- `SessionInfo`: one handshake per TCP connection. It advertises capabilities,
  the unified image/render/window size, and the maximum action-frame count. It
  has no global version field.

### `config`

- `VlaConfig`: reads `config/vla_marionette-client.toml` at startup and exposes
  bridge, capture, state and FOV settings; it writes defaults if missing and
  falls back with a log on invalid values.
- `CaptureConfig` / `CaptureMode`: unified size, `wall_clock` or `tick` mode,
  wall-clock Hz and tick interval.
- `StateConfig` / `StateMode`: State `wall_clock` (`hz`) or `tick`
  (`tick_interval`) pacing; validated independently, so an invalid state section
  never resets capture or FOV.
- `FovConfig` / `FovMode`: startup FOV mode (`fixed`/`vanilla`) and vertical FOV;
  validated independently from the capture section, so an invalid FOV never
  resets the capture size.

### `net`

- `Transport`: dials the host, generates a per-connection session id, sends the
  handshake first, and owns the control/frame queues.
- Control messages use a bounded FIFO. Frames use a single latest-wins slot.
- `InboundRouter`: parses/validates inbound messages on the network thread;
  `ActionChunk` acceptance and receipts are coordinated with the scheduler.
- `ShutdownHandler`: acknowledges a host shutdown request, saves the integrated
  server, and asks the client window to close.

### `capture`

- `CaptureSpec`: unified window/render/JPEG size, 854×480 by default; no scaling
  in the capture path.
- `CaptureScheduler`: wall-clock 5 Hz by default, or every N client ticks.
- `FrameGrabber`: reads the main render target on the render thread.
- `FrameMailbox`: one pending frame; the encoder thread encodes the unified size
  at quality 0.9.
- `CaptureStatus`: valid/reason, last observation id/tick, encoder failures.
- A minimized window renders at 30 FPS instead of Minecraft's 10
  (`FramerateLimitTrackerMixin`), keeping enough render frames for 5 Hz capture.

### `state`

- `ClientClock`: the single logical client-tick counter, incremented at
  `ClientTickEvent.Pre`.
- `StateReporter`: two modes. `wall_clock` is driven by client ticks and render
  frames, throttled by `hz` and kept alive by rendering; `tick` samples only at
  `ClientTickEvent.Post`, after the action scheduler and mouse flush, once every
  `tick_interval` ticks. `tick` is exact and is not backfilled. It reports even
  when no world/player exists.

### `action`

- `ActionFrame`: one tick's held GLFW keys/buttons and raw mouse values.
- `ActionChunk`: an ordered list of 1–20 frames; no separate `ticks` field.
- `ActionScheduler`: one active plus at most one scheduled chunk; receipt
  ordering and `applied`/`ended` timing.
- `ActionReceipt`: wire receipts.
- `InputInjection`: marker used to distinguish bridge-injected events from
  physical events.
- `InputLock`: physical-input lock, actual held-key/button tracking, and the ESC
  manual-takeover latch.
- `McInputInjector`: sends keyboard/mouse events through Minecraft's
  `KeyboardHandler`/`MouseHandler`; maintains a virtual mouse position and
  flushes mouse movement at tick post.

### `query`

- `QueryRequests` / `ChatRequest`: network-thread shape and text validation,
  normalization, and command-vs-chat classification.
- `QueryService`: outstanding-request limit, client-thread hop, and
  session-scoped response delivery.
- `McQueryReader`: block state/properties/block-entity, crosshair target,
  inventory, client settings (including the full current keybind table and
  `hide_gui`), and dispatch of `sendCommand`/`sendChat`.
- `QueryResponses`: response builders for `QueryBlockResult`,
  `QueryInventoryResult`, `QuerySettingsResult`, and `ChatAck`.

### `mc`

- `WindowLayout`: locks the window to the unified size and disables manual
  resizing.
- `HudController`: applies the host's `SetHud` directly to
  `options.hideGui`; no baseline and no `options.txt` write.
- `FovSpec`: effective FOV config for this client run; the renderer reads it every
  frame and `QuerySettings` reads the same value. FOV is a startup setting with no
  runtime control channel.

### `mixin`

- `GameRendererResizeMixin`: forces the render target to the configured unified
  size.
- `CameraFovMixin`: in `fixed` mode, replaces the return value of
  `Camera.calculateFov` to pin the world projection; panorama mode keeps vanilla.
- `FramerateLimitTrackerMixin`: raises the minimized-window frame limit from
  Minecraft's 10 FPS to 30 FPS so 5 Hz capture keeps enough render frames.
- `KeyboardHandlerAccessor` / `MouseHandlerAccessor`: expose Minecraft's private
  input dispatch.
- `KeyboardHandlerMixin` / `MouseHandlerMixin`: drop physical input while the
  automation lock is armed, except ESC.
- `MinecraftFocusMixin`: lets injected mouse input accumulate/flush while the
  window is unfocused or minimized.

## Threading

| Thread | Allowed work |
|---|---|
| Client/render main thread | tick clock, action scheduling, input writes, frame readback, state snapshot, window changes, query reads, chat dispatch |
| Network read/write threads | framing, JSON parse/validate, enqueue, received/rejected receipts, query/chat shape checks and response enqueue; no Minecraft state |
| JPEG encoder thread | RGB resize and JPEG encoding; no Minecraft state or socket |

The render/main thread never waits for the socket. Minecraft operations triggered
from the network side go through `Minecraft.execute(...)`. Query/chat responses
carry the session of the request and are dropped if the connection has since
been replaced.

## Session lifecycle

1. A new TCP connection gets a new `session_id`.
2. The handshake is queued before observations/state.
3. Every accepted action belongs to that connection; `seq` is deduplicated per
   session.
4. Disconnect clears outbound queues, interrupts active/scheduled input, and
   releases held keys/buttons on the main thread.
5. Reconnect creates a fresh session; old frames/receipts/actions are rejected,
   and delayed query/chat responses are dropped by session id.

## Input model

- Each `ActionChunk` frame is one logical client tick.
- `keys` and `buttons` are held states; the injector computes press/release
  edges against the previous tick.
- Mouse `dx/dy` are client-area logical pixels. The mod does not convert model
  units or compute degrees; the host does that conversion.
- Keyboard and mouse events use Minecraft's dispatch, so an open `Screen` receives
  them naturally. GUI and world modes are not separate protocol modes.
- A connected session arms `InputLock`; physical input is dropped while armed.
  Arming (and re-arming after the pause screen closes) also releases all
  Minecraft key mappings, so keys held before the session cannot stay active.
  ESC from the world releases injected input, interrupts the action with
  `manual_takeover`, and allows physical input while the pause screen is open.
  Closing the pause screen re-arms the lock.
- `State.input_locked` reflects whether physical input is suppressed; `State.held`
  reports the actual raw held keys/buttons.
- Injected modifier keys are published per frame (`InjectedInputState`). A mixin
  ORs them into `Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()`, which
  otherwise poll the physical GLFW keyboard; this keeps Ctrl+Q stack drop and
  similar modifier-dependent paths consistent with a physical keyboard.
- The mouse injector keeps a virtual cursor, primes `MouseHandler` with a no-op
  move so the first move after a grab/screen transition is not ignored, and
  flushes pending deltas at tick Post so `ended` covers the last frame's mouse.
- Held button-0 frames drive Minecraft's `continueAttack`. The Minecraft condition also
  needs `mouseHandler.isMouseGrabbed()`; the device layer does not bypass that
  gate.
- `ended` is sent after the last frame's input and mouse flush.

## Invariants

- No freeze/pause/tick-rate override.
- HUD defaults to visible; the host can control it with `SetHud`; the mod keeps
  no baseline and does not write `options.txt`.
- FOV comes from the startup config; `fixed` pins the world projection,
  `vanilla` keeps vanilla behavior; there is no runtime FOV control message and
  no `options.txt` write.
- Render target/window/JPEG use the unified size, 854×480 by default; quality
  0.9.
- 5 Hz capture target; State defaults to a 10 Hz wall-clock target with an
  optional every-N-client-tick mode; 20 TPS logical client tick target.
- Minimized render floor 30 FPS (Minecraft's 10) so capture keeps 6 frames per 200 ms
  sample.
- One active + at most one scheduled action.
- Query/chat responses are keyed by `request_id`, not ordered, and never leak
  into a newer session.
- `ChatAck.ok` means dispatched, not executed; chat is not filtered by the mod.
- HUD visibility is controlled by the host's `SetHud` or Minecraft's F1 key
  (show/hide HUD); the mod keeps no baseline and does not write `options.txt`.
- No model vocabulary or model-side constants in the mod.
