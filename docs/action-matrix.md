# Action Matrix

This is a coverage matrix over the existing raw-input primitives, not a second
action API. It documents how a semantic action is composed from GLFW key codes,
mouse buttons, mouse movement and scroll, and how the result is observed. The
host still sends `ActionChunk` frames; the mod does not interpret the semantic
names in this table.

## Primitives

| Primitive | Wire field | Range |
|---|---|---|
| keyboard press/hold | `frames[i].keys` | GLFW key codes 32–348 |
| mouse buttons | `frames[i].buttons` | 0–7 |
| camera / GUI cursor | `frames[i].mouse.dx/dy` | client-area logical pixels |
| scroll | `frames[i].mouse.scroll` | scroll notches |

A frame is a held state for one client tick. Multiple keys/buttons may be held
together; adjacent chunks keep the same held state when they share a boundary.

`QuerySettings.keybinds` returns the current full `KeyMapping` table. The matrix
uses that table instead of assuming a fixed code for every action. The names
below (`key.forward`, `key.attack`, ...) are vanilla mapping names, not a mod
action vocabulary.

## Matrix

| Action | Raw input | Observation |
|---|---|---|
| forward | hold `key.forward` 6 ticks | horizontal displacement along the facing vector |
| back | hold `key.back` 6 ticks | displacement opposite the facing vector |
| left | hold `key.left` 6 ticks | displacement along the left vector |
| right | hold `key.right` 6 ticks | displacement along the right vector |
| jump | hold `key.jump` 1–2 ticks | `y` rises, `on_ground` false → true |
| sneak | hold `key.sneak` + `key.forward` 10 ticks | speed below walking |
| sprint | hold `key.sprint` + `key.forward` 10 ticks | speed above walking |
| attack | hold `key.attack` button 20 ticks | target block → air, `QueryBlock` |
| use | press `key.use` on a note block | `QueryBlock.properties.note` advances |
| hotbar | press `key.hotbar.N` | `State.hotbar` and `QueryInventory.selected` |
| scroll | one `mouse.scroll` notch | selected hotbar slot changes |
| inventory open/close | press `key.inventory` | `State.screen_open` and `screen` |
| inventory shift-click | move cursor to a slot, hold `key.sneak` + left button | stack moves from hotbar to main inventory |
| drop one | press `key.drop` | selected stack count decreases by one |
| camera | `mouse.dx/dy` | `State.yaw/pitch` changes; position unchanged |
| GUI cursor | `mouse.dx/dy` + button | vanilla `Screen` handles the slot/action |

The test also checks that receipts are `received`/`applied`/`ended` and that no
`rejected` receipt appears.

## Camera mapping

Camera pixels stay host-side. For the reference client (`smooth_camera=false`,
not scoping, first person):

```text
sensMod   = (sensitivity * 0.6 + 0.2)^3
yaw/pitch = pixels * sensMod * 1.2      (normal view)
          = pixels * sensMod * 0.15     (scoping)
```

`invert_mouse_x/y` flips the sign; pitch is clamped to ±90. `smooth_camera`
changes the response curve and should be disabled for deterministic tests.

The test uses `QuerySettings` values rather than hard-coded sensitivity.

## GUI coordinates

`QuerySettings.gui_scale`, `gui_width` and `gui_height` describe the current GUI.
A GUI coordinate maps to a client-area pixel as:

```text
screen_pixel = gui_coordinate * gui_scale
```

The test targets the vanilla inventory layout:

```text
left = (gui_width  - 176) / 2
top  = (gui_height - 166) / 2
hotbar slot 0 = (left + 8,       top + 142)
main slot 9   = (left + 8,       top + 84)
```

To make cursor targeting deterministic while a screen is open, the test first
sends a very large negative delta to clamp the virtual cursor to `(0, 0)`, then
moves to the target slot. This is a host-side technique; the mod only carries
the deltas.

## Session and focus conditions

- The host waits for `State.input_locked == true` before sending actions. A
  `State` sampled during session setup may still report false.
- Normal-window runs were observed with the client unfocused and occluded by
  other application windows; the functional checks passed. A separate cadence
  comparison for that condition was not made.
- The minimized subset covers forward, jump, attack, use, camera and hotbar;
  minimized capture itself is covered by
  [background-acceptance.md](background-acceptance.md).

## Non-goals

- GUI text input (`charTyped`/IME/dead keys); chat and commands use `Chat`.
- Fn/media keys and mouse buttons beyond 0–7.
- Entity queries, full NBT/components and general world info.
- Multiplayer and long-duration soak tests.
- Semantic action fields on the wire (`forward`, `attack`, `camera`, ...).

## Running

```powershell
node tools/matrix-check.mjs --port 25580 --out 'runs/matrix' [--shutdown]
node tools/matrix-check.mjs --port 25580 --out 'runs/matrix-min' --minimized [--shutdown]
```

The minimized run expects the window to be minimized before the host starts.
The report records the settings snapshot, the keybind count, every check, and
the receipts.

## Results

Reference client, 60-entry keybind table:

| Run | Checks | Actions | Result |
|---|---|---|---|
| normal window | 123 | 22 | passed |
| minimized | 69 | 10 | passed |

Foreground observations included:

- forward/back/left/right: 1.283–1.288 blocks over six ticks, direction dot 1.000;
- jump rise 1.249 blocks, airborne then landed;
- sneak 0.795 vs walk 2.146 blocks; sprint 2.790 vs walk 2.146;
- dirt → air under a 20-tick attack hold;
- note block `note` 0 → 1 after `key.use`;
- hotbar key 3 selected slot 2; one scroll notch moved it to 3;
- inventory shift-click moved dirt from hotbar slot 0 to main slot 9;
- Q dropped one dirt (2 → 1);
- camera 100/50 pixels produced yaw +15.00° and pitch +7.50° at sensitivity 0.5,
  with no player movement.

The minimized subset passed forward, jump, attack, use, camera, hotbar/scroll,
and inventory open/close. The client was minimized with Win32 `ShowWindow` and
verified with `IsIconic` before the run.

The matrix exposed one real bug: the first mouse delta after a screen/grab
transition could be consumed by vanilla `ignoreFirstMove`. `McInputInjector`
now sends a no-op move before the real delta (D24); the camera and inventory
checks pass with that fix.

