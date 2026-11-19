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
  image/render/window sizes, and the maximum action-frame count. It has no
  global version field.

### `net`

- `Transport`: dials the host, generates a per-connection session id, sends the
  handshake first, and owns the control/frame queues.
- Control messages use a bounded FIFO. Frames use a single latest-wins slot.
- `InboundRouter`: parses/validates inbound messages on the network thread;
  `ActionChunk` acceptance and receipts are coordinated with the scheduler.
- `ShutdownHandler`: acknowledges a host shutdown request, saves the integrated
  server, and asks the client window to close.

### `capture`

- `CaptureSpec`: fixed render/window 640×360 and JPEG output 854×480.
- `CaptureScheduler`: wall-clock 5 Hz sampling on `RenderFrameEvent.Post`.
- `FrameGrabber`: reads the main render target on the render thread.
- `FrameMailbox`: one pending frame; the JPEG encoder thread scales and encodes
  with quality 0.9.
- `CaptureStatus`: valid/reason, last observation id/tick, encoder failures.
- A minimized window renders at 30 FPS instead of vanilla's 10
  (`FramerateLimitTrackerMixin`). Two 30 s minimized runs measured 4.83 and
  4.85 Hz with p95 233.8/233.7 ms; 10 FPS produced only 4.10/4.11 Hz.

### `state`

- `ClientClock`: the single logical client-tick counter, incremented at
  `ClientTickEvent.Pre`.
- `StateReporter`: wall-clock-throttled 10 Hz report driven by both client ticks
  and render frames. It reports even when no world/player exists.

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
- `McInputInjector`: sends keyboard/mouse events through vanilla
  `KeyboardHandler`/`MouseHandler`; maintains a virtual mouse position and
  flushes mouse movement at tick post.

### `query`

- `QueryRequests` / `ChatRequest`: network-thread shape and text validation,
  normalization, and command-vs-chat classification.
- `QueryService`: outstanding-request limit, client-thread hop, and
  session-scoped response delivery.
- `McQueryReader`: block state/properties/block-entity, crosshair target,
  inventory, client settings (including the full current keybind table), and
  dispatch of `sendCommand`/`sendChat`.
- `QueryResponses`: response builders for `QueryBlockResult`,
  `QueryInventoryResult`, `QuerySettingsResult`, and `ChatAck`.

### `mixin`

- `GameRendererResizeMixin`: forces the render target to 640×360.
- `FramerateLimitTrackerMixin`: raises the minimized-window frame limit from
  vanilla 10 FPS to 30 FPS so 5 Hz capture keeps enough render frames.
- `KeyboardHandlerAccessor` / `MouseHandlerAccessor`: expose vanilla private
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
- Keyboard and mouse events use vanilla dispatch, so an open `Screen` receives
  them naturally. GUI and world modes are not separate protocol modes.
- A connected session arms `InputLock`; physical input is dropped while armed.
  Arming (and re-arming after the pause screen closes) also releases all vanilla
  key mappings, so keys held before the session cannot stay active.
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
- Held button-0 frames drive vanilla `continueAttack`. The vanilla condition also
  needs `mouseHandler.isMouseGrabbed()`; normal-window and minimized runs broke
  hand-mined dirt and stone (stone through eight chained chunks), so the gate was
  satisfied in those flows and no bypass is applied.
- `ended` is sent after the last frame's input and mouse flush.

## Invariants

- No freeze/pause/tick-rate override.
- HUD is never hidden.
- Render target/window 640×360; JPEG 854×480; quality 0.9.
- 5 Hz capture target; 10 Hz state target; 20 TPS logical client tick target.
- Minimized render floor 30 FPS (vanilla 10) so capture keeps 6 frames per 200 ms
  sample.
- One active + at most one scheduled action.
- Query/chat responses are keyed by `request_id`, not ordered, and never leak
  into a newer session.
- `ChatAck.ok` means dispatched, not executed; chat is not filtered by the mod.
- No model vocabulary or model-side constants in the mod.
