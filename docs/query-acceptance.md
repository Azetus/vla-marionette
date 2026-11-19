# Query and Chat Acceptance

The query/chat acceptance exercises `QueryBlock`, `QueryInventory` and `Chat`
against a live client without sending any `ActionChunk`. It is separate from the
M1 loop acceptance: M1 still covers connect, capture, raw input and receipts.

## Prerequisites

- A built jar at `build/libs/vla-marionette.jar` and a running client that loads
  it.
- A superflat test world with cheats enabled (the commands used by the check
  require permission level 2).
- A free bridge port (default 25580).
- The client must be in the world with no screen open and `on_ground=true`.

The check starts the client first, then starts its host listener; the mod retries
the connection every two seconds.

## Running

Single run:

```powershell
node tools/query-check.mjs --port 25580 --out '<output-dir>'
```

With a graceful save and shutdown after a pass:

```powershell
node tools/query-check.mjs --port 25580 --out '<output-dir>' --shutdown
```

The script writes `report.json` with every check, every response, and the
before/after state. Exit code 0 means all checks passed.

The script resets its target coordinate to air before reading it, so repeated
runs in the same world are deterministic. It also clears the inventory and gives
a known stack, so a previous run cannot change the expected counts.

## Checks

| Area | Assertion |
|---|---|
| Handshake | 854×480 JPEG spec; render target 640×360; capabilities include `query_block`, `query_inventory`, `chat`, `query_settings` |
| State | in world, screen closed, on ground, input lock armed |
| Settings | `QuerySettings` returns a valid game mode, sensitivity 0–1, boolean invert/smooth/scoping flags, a known camera type, and positive GUI scale/dimensions |
| Chat command | `clear`, `give`, `setblock`, `tp` return `ChatAck(ok=true, kind=command)` |
| Chat chat | a line without `/` returns `kind=chat` |
| Inventory | after `clear` the slots array is empty; after `give` 5 stone the stone is in the hotbar with count 5 and `selected=0` |
| Block air | position query returns `minecraft:air`, `{}` properties, `null` block entity |
| Block properties | `oak_log` returns `properties.axis="y"` |
| Block entity | `chest` returns `block_entity="minecraft:chest"` |
| Bounds | `y=400` → `out_of_bounds` |
| Chunk | a far position → `chunk_not_loaded` |
| Shape | short `pos` → `bad_shape` with the request id; missing `request_id` → uncorrelated `bad_shape` |
| Text | empty `Chat` text → `bad_text` with `kind=null` |
| Crosshair | face west with a known block in front; query without `pos` returns the same block, `face="east"`, and a plausible distance |
| No side effects | no `ActionChunk` was sent; the player does not move; the input lock stays armed; the stone stack is unchanged |

## Results

- Isolated client (fresh game directory, copied test world): all checks passed.
- Installed client: two consecutive runs passed, each followed by
  `Shutdown(save=true)`; the integrated server logged `Saving worlds`,
  `All chunks are saved`, and the client exited.
- After `QuerySettings` was added, the installed client run passed 55 checks;
  the settings response reported `gamemode=survival`, `sensitivity=0.5`,
  `camera_type=first_person`, `gui_scale=1`, `gui_width=640`, `gui_height=360`.
  The earlier 46-check runs above were before the settings query existed.

## What is not machine-verified

- Plain chat delivery is not observable through the protocol. The check asserts
  `ChatAck(ok=true, kind=chat)` and leaves visual confirmation to the JPEG
  observation or the game chat.
- `ChatAck(ok=true)` does not prove server execution. Command effects are
  verified indirectly through `QueryBlock`/`QueryInventory`; permission or syntax
  failures appear only in the game chat.
- The physical input lock is asserted from `State.input_locked`; manual ESC
  takeover checks remain in [M1 acceptance](m1-acceptance.md).

## Troubleshooting

| Symptom | Check |
|---|---|
| Commands have no effect but ack is ok | Cheats disabled, wrong permission level, or a syntax error; inspect the game chat. Also check that the text starts with `/`; without it the line is plain chat. |
| Commands have no effect but ack is ok | Cheats disabled, wrong permission level, or a syntax error; inspect the game chat. |
| `chunk_not_loaded` for a nearby position | Wait for the client to receive the chunk, or query closer. |
| `no_target` for crosshair | Aim at a block within reach; the crosshair uses the vanilla pick result. |
| Inventory counts differ | The client has not received the inventory update yet; wait a few ticks. |
| Repeated run fails on the air check | Use the current script, which resets the target block first. |
