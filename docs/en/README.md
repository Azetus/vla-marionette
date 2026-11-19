# VLA-Marionette Documentation

> This directory contains the English technical documentation for the current
> codebase. Protocol fields, message names, class names, commands and code
> identifiers stay in English. Chinese documentation is under `docs/zh/`; the
> English README is [`README_EN.md`](../../README_EN.md).

VLA-Marionette is a client-side device layer for Minecraft 26.1.2 /
NeoForge 26.1.2.126. It captures frames, reports state, injects raw input,
schedules tick-level actions, and returns receipts. It does not contain model
calls, prompts, action-text parsing, model vocabulary, or model-side conversion
constants.

## Contents

- [Architecture](architecture.md)
- [Protocol](protocol.md)
- [Actions](actions.md)

## Current implementation

- Capture: 640×360 render target, 854×480 JPEG at quality 0.9, 5 Hz target;
  minimized windows render at a 30 FPS floor.
- State: 10 Hz target with position, rotation, health, ground state, hotbar,
  screen, `input_locked`, `held`, and capture status.
- Input: per-tick GLFW key codes, mouse buttons 0–7, client-area pixel movement,
  and scroll.
- Queries: `QueryBlock`, `QueryInventory`, `QuerySettings`; the settings query
  includes the full current keybind table.
- Chat/commands: one `Chat` message; a leading `/` selects the command path.
- Session: physical input lock, ESC takeover, disconnect cleanup, reconnect as
  a new session, graceful shutdown.

## Non-goals

- GUI text input (`charTyped`, IME, dead keys); chat and commands use `Chat`.
- Fn/media keys and mouse buttons beyond 0–7.
- Entity queries, full NBT/components and general world info.
- `State.sneaking` / `State.sprinting`; infer movement mode from speed.
- Multiplayer and long soak runs.
- Semantic action fields (`forward`, `attack`, `camera`, ...); the wire carries
  raw frames only.
