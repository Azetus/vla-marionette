# Actions

This is a composition reference for the existing raw-input primitives, not a
second action API. It shows how a semantic action is composed from GLFW key
codes, mouse buttons, mouse movement and scroll. The host still sends
`ActionChunk`; the mod does not interpret the semantic names.

## Primitives

| Primitive | Wire field | Range |
|---|---|---|
| keyboard press/hold | `frames[i].keys` | GLFW key codes 32–348 |
| mouse buttons | `frames[i].buttons` | 0–7 |
| camera / GUI cursor | `frames[i].mouse.dx/dy` | client-area logical pixels |
| scroll | `frames[i].mouse.scroll` | scroll notches |

A frame is a held state for one client tick. Multiple keys/buttons may be held
together; adjacent chunks keep the same held state when they share a boundary.

`QuerySettings.keybinds` returns the current full `KeyMapping` table. The host
should take action keys from that table instead of assuming fixed codes. The
names below (`key.forward`, `key.attack`, ...) are Minecraft's mapping names, not a
mod action vocabulary.

## Actions

| Action | Raw input | Observable result |
|---|---|---|
| forward | hold `key.forward` | horizontal displacement along facing |
| back | hold `key.back` | displacement opposite facing |
| left | hold `key.left` | displacement along the left vector |
| right | hold `key.right` | displacement along the right vector |
| jump | hold `key.jump` 1–2 ticks | `y` rises, `on_ground` false → true |
| sneak | hold `key.sneak` + `key.forward` | speed below walking |
| sprint | hold `key.sprint` + `key.forward` | speed above walking |
| attack | hold `key.attack` | target block changes, `QueryBlock` |
| use | press `key.use` on an interactive block | block state changes, `QueryBlock` |
| hotbar | press `key.hotbar.N` | `State.hotbar` and `QueryInventory.selected` |
| scroll | `mouse.scroll` | selected hotbar slot changes |
| inventory open/close | press `key.inventory` | `State.screen_open` and `screen` |
| inventory shift-click | move cursor to a slot, hold `key.sneak` + left button | `QueryInventory` slot change |
| drop one | press `key.drop` | selected stack count decreases by one |
| camera | `mouse.dx/dy` | `State.yaw/pitch` changes; position unchanged |
| GUI cursor | `mouse.dx/dy` + button | Minecraft's `Screen` handles the slot/action |

## Camera mapping

Camera pixels stay host-side. For the reference client
(`smooth_camera=false`, not scoping, first person):

```text
sensMod   = (sensitivity * 0.6 + 0.2)^3
yaw/pitch = pixels * sensMod * 1.2      # normal view
          = pixels * sensMod * 0.15     # scoping
```

`invert_mouse_x/y` flips the sign; pitch is clamped to ±90. `smooth_camera`
changes the response curve. The host should use the `QuerySettings` sensitivity,
not a hard-coded value.

## GUI coordinates

`QuerySettings.gui_scale`, `gui_width` and `gui_height` describe the current GUI.
A GUI coordinate maps to a client-area pixel as:

```text
screen_pixel = gui_coordinate * gui_scale
```

Reference Minecraft inventory layout:

```text
left = (gui_width  - 176) / 2
top  = (gui_height - 166) / 2
hotbar slot 0 = (left + 8,       top + 142)
main slot 9   = (left + 8,       top + 84)
```

If the host needs deterministic GUI cursor targeting, it can send a very large
negative delta while a screen is open to clamp the virtual cursor to `(0, 0)`,
then move to the target slot. This is a host-side technique; the mod only
carries the deltas.

## Session conditions

- The host waits for `State.input_locked == true` before sending actions. A
  `State` sampled during session setup can still report false.
- Injected input bypasses the window focus check, so an unfocused or minimized
  window does not block input; GUI/world semantics remain with Minecraft's
  `Screen`/`KeyMapping`.
- HUD visibility is not a raw action; the host sets it with `SetHud` and reads
  the current value from `QuerySettings.hide_gui`; Minecraft's F1 key
  (show/hide HUD) can still change it.
- FOV is a startup setting (`[fov]`), not a raw action; `fixed` mode pins the world
  projection, so the spyglass does not zoom and sprinting/flying/water/death do
  not change the image FOV; the host reads `QuerySettings.fov` and `fov_mode`.
- A minimized window renders at a 30 FPS floor to keep 5 Hz capture supplied
  with render frames.

## Non-goals

- GUI text input (`charTyped`, IME, dead keys); chat and commands use `Chat`.
- Fn/media keys and mouse buttons beyond 0–7.
- Entity queries, full NBT/components and general world info.
- Multiplayer and long soak runs.
- Semantic action fields on the wire (`forward`, `attack`, `camera`, ...).
