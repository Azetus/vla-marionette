# Same-Build Regression

This record confirms that every implemented capability still works on one built
and deployed jar, first with a normal window and then with the window minimized.
The jar is not rebuilt between the two halves of the run.

## Matrix

All runs used the same installed client jar and the superflat test world.

| Capability | Normal window | Minimized |
|---|---|---|
| M1 loop | 3/3 passed | 1/1 passed |
| Query / chat | 46 checks passed | 46 checks passed |
| Minimized capture / forward | — | 30 s, 145 observations, 4.82 Hz |
| Continuous mining (dirt + stone) | 62 checks passed | 62 checks passed |
| Graceful shutdown | passed | passed |

This regression predates the `QuerySettings` addition; the current query check
includes it and reports 55 checks (see [query-acceptance.md](query-acceptance.md)).

## Normal window

M1 three-run acceptance:

| Run | Distance | Direction dot | dy | Receipts |
|---|---|---|---|---|
| 1 | 1.157 | 1.000 | 0 | received/applied/ended |
| 2 | 1.158 | 1.000 | 0 | received/applied/ended |
| 3 | 1.157 | 1.000 | 0 | received/applied/ended |

The last run used `Shutdown(save=true)`; the integrated server saved and the
client exited.

Query and chat: 46 checks passed, covering `QueryBlock` (position and
crosshair), `QueryInventory`, `Chat` command/chat paths, negative shapes, and
the no-movement assertion.

Continuous mining: dirt broke in one 20-tick chunk and stone in eight chained
20-tick chunks (160 ticks); both targets became `minecraft:air`, the button
stayed held, every chunk received `received`/`applied`/`ended`, and the
crosshair reported face `east` at distance 1.5.

## Minimized window

M1 one run: 1.157 blocks, direction dot 1.000, dy 0, post-action frame received.

Query and chat: 46 checks passed at the 30 FPS minimized floor.

Capture: 30 s, 145 observations, 4.82 Hz, p50 200.9 ms, p95 233.8 ms, maximum
235.3 ms, no observation-id gaps, no encoder failures, all 854×480. Forward
moved 1.157 blocks with direction dot 1.000 and dy 0.

Continuous mining: dirt one chunk and stone eight chained chunks, both to
`minecraft:air`, with the same receipt and held-button evidence as the focused
run.

`Shutdown(save=true)` saved the integrated server and the client exited.

## Scope

- One machine, one Minecraft/NeoForge version, one superflat world, integrated
  server.
- Window minimization was done with Win32 `ShowWindow` and verified with
  `IsIconic` before the minimized runs.
- The minimized capture run is one 30 s sample in this regression; earlier
  standalone runs measured 4.83 and 4.85 Hz.
- A visible-but-unfocused window, long-duration runs, multiplayer servers and
  tool-specific mining are not covered.
