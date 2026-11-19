# Continuous Mining Acceptance

This check exercises continuous block breaking through held raw left-button
frames. It places a target block, confirms it with `QueryBlock`, holds button 0
through chained `ActionChunk`s, and reads the target again. It covers a
hand-mined dirt block (one 20-tick chunk) and a hand-mined stone block (about
150 ticks, eight chained chunks).

## Procedure

1. Build and deploy the current jar.
2. Launch the client with the test world and wait until the player is in world.
3. Start the host:

   ```powershell
   node tools/dig-check.mjs --port 25580 --out '<output-dir>' \
     --blocks minecraft:dirt,minecraft:stone --chunks 1,8 [--shutdown]
   ```

4. For the background run, minimize the window before starting the host; for the
   foreground run, keep the window focused.

For each block the host performs the setup itself:

- `/clear @s`, so the main hand is empty and stone is mined by hand;
- `QueryInventory` asserts that the selected slot is empty;
- `/setblock 198 -59 200 air`, then `/setblock 198 -59 200 <block>`;
- `/tp @s 200.5 -60 200.5 90 0` (two blocks away, facing west at eye level);
- `QueryBlock` at the target asserts the placed block;
- a crosshair `QueryBlock` asserts the same position, face `east`, distance 1.5;
- for each chunk, `ActionChunk` with 20 frames of `{"buttons":[0]}`. The first
  chunk starts six ticks ahead and each next chunk starts exactly 20 ticks after
  the previous one, so the button stays held across chunk boundaries;
- after the last `ended`, a position `QueryBlock` must return `minecraft:air`.

Dirt breaks by hand in about 0.75 s. Stone has hardness 1.5 and cannot be
harvested by hand, so breaking takes about 150 ticks; eight 20-tick chunks
(160 ticks) are used, with no release tick between them.

## Thresholds

| Area | Assertion |
|---|---|
| Hand | selected slot is empty before mining |
| Target before | the requested block at the requested position |
| Aim | crosshair sees the same position, face `east`, distance 1.5 |
| Input | `State.held.buttons` contains 0 during the chain |
| Receipts | every chunk: `received → applied(start) → ended(start+20)`; no rejection |
| Chaining | each next chunk starts exactly 20 ticks after the previous one |
| Target after | `minecraft:air` |
| Lock | `input_locked=true` throughout |

## Results

Initial single-block runs, each with 18 checks:

| Run | Window | Target before | Target after | Chunks | Held |
|---|---|---|---|---|---|
| 1 | focused | dirt | air | 1 | `[0]` |
| 2 | minimized after world load | dirt | air | 1 | `[0]` |
| 3 | minimized before world load | dirt | air | 1 | `[0]` |

Two-block regression on the same build, each with 62 checks:

| Run | Window | Block | Chunks | Ticks | Target after | Held | Receipts |
|---|---|---|---|---|---|---|---|
| 1 | focused | dirt | 1 | 20 | air | `[0]` | 3 |
| 1 | focused | stone | 8 | 160 | air | `[0]` | 24 |
| 2 | minimized | dirt | 1 | 20 | air | `[0]` | 3 |
| 2 | minimized | stone | 8 | 160 | air | `[0]` | 24 |

The crosshair reported `face=east` and `distance=1.5` in every run. The chained
chunks received one `received`/`applied`/`ended` set each; there were no
rejections and no gap tick between chunks.

## The mouse-grab gate

Vanilla continues an attack only when all of these hold:

```text
screen == null
options.keyAttack.isDown()
mouseHandler.isMouseGrabbed()
```

The results prove the gate was satisfied for multiple ticks: the stone block
takes about 150 ticks by hand, and it broke while the button stayed held across
eight chunks. A single `startAttack` cannot produce that; `continueAttack` must
have run on every tick. No mouse-grab bypass was needed in the tested flows,
including the run where the window was already minimized before the world
finished loading.

The check does not expose `isMouseGrabbed` directly; the conclusion is inferred
from the block breaking, the held button state and the receipts. A future run
that leaves the mouse ungrabbed would fail the air check and should be diagnosed
against this gate.

## Limits

- Only a hand-mined dirt block and a hand-mined stone block; tool-specific
  mining (pickaxe, axe, shovel) and block drops are not separately asserted.
- Mining multiple different blocks in one continuous hold, and mining while
  walking, are not covered.
- A visible but unfocused window is not separately measured; minimized is the
  harder case and implies the window is not focused.
- The test uses `Chat` for setup, which also exercises the chat path in the
  minimized case.
