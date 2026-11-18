# Decisions

This file records the design decisions that shape the current implementation.
Each decision states the choice and the reason; it does not describe future
features.

## D1. Device layer only

The mod captures frames, reports state, injects input, schedules tick-level
actions, and returns receipts. Model calls, prompts, action-text parsing, model
vocabulary, and model-unit conversion stay in the host.

Reason: changing the model must not require changing the Minecraft client mod.

## D2. No freeze

There is no `Freeze`/`Unfreeze`, no cancellation of `Minecraft.tick()`, and no
`tickRateManager` override. The world advances while the host is reasoning.
Logical ticks and state reports continue to grow.

## D3. Fixed visual specification

- render target: 640×360
- window: 640×360
- observation: JPEG 854×480, quality 0.9
- HUD is never hidden

The render target is forced with a mixin so UI scaling cannot change the
observation shape.

## D4. Capture cadence and queues

Capture targets 5 Hz by wall clock. Frames have one latest-wins transport slot;
the JPEG encoder has one latest-frame mailbox. A slow consumer loses old frames,
never the newest.

## D5. One session per TCP connection

Each connection generates a new `session_id`. Reconnect is a new session.
Disconnect clears outbound queues, interrupts active/pending input, and releases
keys/buttons on the main thread. Old frames, receipts, and actions cannot enter a
new session.

## D6. No global protocol version field

Compatibility is carried by `SessionInfo` fields and `capabilities`. The mod
advertises only implemented capabilities. A version integer would duplicate the
field/capability contract and add maintenance without preventing field
misinterpretation.

## D7. Raw per-tick input frames

`ActionChunk` carries ordered frames of raw input, not semantic controls such as
`forward`, `attack`, or `camera`.

Each frame contains:

- held GLFW keyboard codes;
- held GLFW mouse button codes;
- client-area logical pixel mouse delta and scroll.

Reason: the same raw events must work in the world and inside a GUI. The mod
must not classify “slot click” versus “ordinary mouse movement”; vanilla
`Screen` and `KeyMapping` decide the meaning. The host owns model-token to
key-code and model-unit to pixel conversion.

## D8. Tick is the logical client tick

A frame is one execution of `Minecraft.tick()` (from `ClientTickEvent.Pre` to
`Post`), nominally 20 TPS. It is not a render frame and not a wall-clock 50 ms.
`start_tick` uses this counter.

Consequence: a frame stores one held state per tick. Multiple press/release
edges of the same key inside one tick are not representable. This is documented
and accepted for M1.

## D9. GUI is not a separate input mode

Opening a screen does not interrupt raw input. Keys/buttons/mouse are dispatched
through vanilla input handlers; the open `Screen` consumes them. `State.screen`
and `State.screen_open` let the host observe the UI. There is no mod-side
“slot click” or “GUI action” classification.

## D10. Receipts are ordered and terminal

- `received` is queued inside the scheduler acceptance lock.
- `applied` is sent at the first frame's `ClientTickEvent.Pre`.
- `ended` is sent at the last frame's `ClientTickEvent.Post`, after the mouse
  flush.
- `rejected` and `interrupted` are terminal.

A `seq` is consumed only when accepted. Rejected attempts can be retried; an
accepted duplicate is rejected.

## D11. One active plus one scheduled chunk

At most one chunk is active and one is scheduled. `start_tick <= current tick` is
`late`; it is not shifted. A chunk may not be replaced implicitly.

This keeps receipt semantics and input ownership unambiguous.

## D12. Mouse movement is scheduled, not left to the render loop

Vanilla `MouseHandler.onMove` takes absolute coordinates, accumulates only while
the window is active, and is consumed later by the render loop. Calling it alone
does not guarantee delivery at the target tick or in the background.

The mod maintains a virtual cursor/delta, flushes it at tick post through
vanilla mouse handling, and sends `ended` afterwards. Injected mouse events also
bypass the focus check. Continuous mining still has an independent mouse-grab
gate and is not implied by background camera rotation.

## D13. Graceful shutdown

`Shutdown(save=true)` makes the mod acknowledge, save the integrated server, and
request window close. Window close uses Minecraft's normal destroy path. A
standalone stop script first sends `WM_CLOSE`; forced PID termination is a
timeout fallback only. Processes are validated by PID/run id, never killed by
name.

## D14. Isolated and installed acceptance

The launcher can create a fresh isolated game directory from a world source, or
use the installed client directly. The installed mode is the final acceptance
mode; the isolated mode is for development and tooling rehearsal. Backups of the
mod jar use a non-`.jar` suffix so NeoForge cannot load both.

## D15. Test client policy

The client is a bridge target, not a human-facing game client. The mod disables
focus-loss auto-pause at the first client tick. Test options also disable focus
pause and set the inactivity limit to the minimized-window policy. The HUD stays
visible, and `options.txt` is not otherwise rewritten.

## D16. Physical input lock and manual takeover

A connected session arms a physical input lock. Physical keyboard/mouse events
are dropped; injected events carry `InputInjection` and pass. ESC is the only
escape: from the world it releases injected input, interrupts the action with
`manual_takeover`, opens the pause screen, and allows physical input while that
screen is open. Closing the pause screen re-arms the lock. New action chunks
during takeover are rejected.

`State.input_locked` reports whether physical input is currently suppressed.
`State.held` reports the actual raw `keys`/`buttons` currently held, not merely
what the last action requested.

Arming the lock releases all vanilla key mappings once on the client thread, and
re-arming after the ESC pause screen does the same. Without this, a key held
before the session (for example, walking during the unconnected launch window)
stays down: the lock blocks its release edge as well, so the player keeps moving
after automation takes over.

The lock is connection-scoped: it arms on session connect and disarms on
disconnect. Before the first connection and after a disconnect the client is
human-playable by design.

## D17. Queries are client-thread snapshots with request ids

`QueryBlock` and `QueryInventory` are asynchronous request/response messages
keyed by `request_id`. Shape validation runs on the network thread; the actual
reads run on the client thread and responses go through the control queue.

Reason: reading `level`/`player` from the network thread would race with the
client tick. The response carries the session of the request, so a delayed reply
cannot enter a newer connection.

Consequences: responses are not ordered relative to each other or to `State`;
the host correlates by `request_id`. At most 32 requests may be outstanding.
A query returns client-side state, not an authoritative server snapshot, and no
NBT. Crosshair queries read the vanilla per-frame pick result and may be one
frame stale.

## D18. Unified chat entry; `ok` means dispatched

One `Chat` message carries a single line of text. A leading `/` dispatches
`ClientPacketListener.sendCommand`; otherwise the line goes through `sendChat`.
This mirrors vanilla `ChatScreen`. There is no command whitelist or denylist and
no command-name parsing.

Reason: a command is a chat line with a leading slash at the device layer. A
whitelist would be a second, version-coupled source of command truth with no
real security benefit (whitelisted commands such as `give` can still change the
world arbitrarily). Policy belongs to the host.

Consequences: `stop`, `kick`, `ban` and similar commands reach the server
directly; a normal close must use `Shutdown(save=true)`. `ChatAck.ok` only means
the line was handed to the vanilla path; it does not prove execution. Plain chat
is public in multiplayer and subject to chat signing, filters and rate limits.
The mod does not open the chat screen and does not fold internal whitespace.

## Known gaps

These are current limitations, not decisions to keep forever:

- no multi-chunk concurrency;
- no sub-tick event stream; one held state per tick is intentional;
- background/minimized capture and continuous mining are not fully verified;
- no freeze is intentional and permanent for v1;
- query/chat responses are best effort during shutdown;
- command dispatch is not sandboxed; the host is responsible for what it sends.
