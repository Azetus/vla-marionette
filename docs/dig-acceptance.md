# Continuous Mining Acceptance

This check exercises continuous block breaking through held raw left-button
frames: place a target block, confirm it with `QueryBlock`, hold button 0 through
one `ActionChunk`, then read the target again. It is run foreground first and
then with the window minimized.

## Procedure

1. Build and deploy the current jar.
2. Launch the client with the test world and wait until the player is in world.
3. Start the host:

   ```powershell
   node tools/dig-check.mjs --port 25580 --out '<output-dir>' [--shutdown]
   ```

4. For the background run, minimize the window before starting the host; for the
   foreground run, keep the window focused.

The host performs the setup itself:

- `/gamemode survival` so breaking uses normal survival timing;
- `/setblock 198 -59 200 air`, then
  `/setblock 198 -59 200 minecraft:dirt`;
- `/tp @s 200.5 -60 200.5 90 0` (two blocks away, facing west at eye level);
- `QueryBlock` at the target asserts `minecraft:dirt`;
- a crosshair `QueryBlock` asserts the same position, face `east`, distance 1.5;
- one `ActionChunk` with 20 frames of `{"buttons":[0]}`, starting six ticks ahead;
- after `ended`, a position `QueryBlock` must return `minecraft:air`.

Dirt is used because it breaks by hand in about 0.75 s, so one 20-tick chunk
exercises continuous attack without giving the player a tool.

## Thresholds

| Area | Assertion |
|---|---|
| Target before | `minecraft:dirt` at the requested position |
| Aim | crosshair sees the same position, face `east`, distance 1.5 |
| Input | `State.held.buttons` contains 0 during the chunk |
| Receipts | `received → applied(start) → ended(start+20)`; no rejection |
| Target after | `minecraft:air` |
| Lock | `input_locked=true` throughout |

## Results

Three runs, each with 18 checks:

| Run | Window | Target before | Target after | Attempts | Held | Receipts |
|---|---|---|---|---|---|---|
| 1 | focused | dirt | air | 1 | `[0]` | received/applied/ended |
| 2 | minimized after world load | dirt | air | 1 | `[0]` | received/applied/ended |
| 3 | minimized before world load | dirt | air | 1 | `[0]` | received/applied/ended |

All runs used one 20-tick chunk; the block broke before a second chunk was
needed. The crosshair reported `face=east` and `distance=1.5` in every run.

## The mouse-grab gate

Vanilla continues an attack only when all of these hold:

```text
screen == null
options.keyAttack.isDown()
mouseHandler.isMouseGrabbed()
```

The single-block result proves the gate was satisfied for multiple ticks:
`startAttack` only begins the destroy progress, and dirt takes many ticks to
break in survival, so `continueAttack` must have run. No mouse-grab bypass was
needed in the tested flows, including the run where the window was already
minimized before the world finished loading.

The check does not expose `isMouseGrabbed` directly; the conclusion is inferred
from the block breaking, the held button state and the receipts. A future run
that leaves the mouse ungrabbed would fail the air check and should be diagnosed
against this gate.

## Limits

- One hand-breakable block per run; tool-specific mining (pickaxe, axe, shovel)
  and block drops are not separately asserted.
- Mining multiple blocks in a row, and mining while walking, are not covered.
- A visible but unfocused window is not separately measured; minimized is the
  harder case and implies the window is not focused.
- The test uses `Chat` for setup, which also exercises the chat path in the
  minimized case.
