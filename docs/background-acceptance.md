# Minimized Capture and Forward Acceptance

This check answers one question: with the client window minimized, can the
device layer still produce observations at the 5 Hz target and inject a forward
action? It is a post-M1 check; the M1 loop acceptance in
[m1-acceptance.md](m1-acceptance.md) covers the normal client window.

## Why it matters

Capture samples on render frames. Vanilla throttles a minimized window to 10 FPS,
so a 5 Hz sample (200 ms) has only two frames of headroom. A sample that misses
its frame slips to the next one, producing a 300 ms gap. The measured cadence is
therefore the deciding input for raising the minimized frame limit.

## Procedure

1. Build and deploy the current jar.
2. Launch the client with the test world and wait until the player is in world.
3. Minimize the Minecraft window; `IsIconic` must be true.
4. Start the host:

   ```powershell
   node tools/background-check.mjs --port 25580 --out '<output-dir>' --seconds 30 --frames 6 --shutdown
   ```

5. The host waits for a stable in-world state, records 30 s of observations,
   teleports to an empty superflat area (`/gamemode survival`,
   `/tp @s 200.5 -60 200.5 0 0`), sends one six-tick W chunk while still
   minimized, writes `report.json`, and requests a graceful shutdown.

The launcher minimizes the window and confirms `IsIconic` before starting the
host. The host cannot query the game window state directly; it only measures
capture cadence. The pre-change and post-change cadence difference (4.1 Hz at
the vanilla 10 FPS limit, 4.83–4.85 Hz at 30 FPS) is the evidence that the
render bound changed as expected.

## Thresholds

| Area | Threshold |
|---|---|
| Precondition | `IsIconic` is true before the host starts |
| Observations | at least 4.5 × seconds (135 in 30 s) |
| Cadence | ≥ 4.5 Hz by sample timestamps |
| Interval p95 | ≤ 300 ms |
| Maximum interval | ≤ 500 ms |
| Frames | all 854×480 JPEG; no encode failures |
| State | capture valid for every state; no screen; input lock armed |
| Forward | received → applied(start) → ended(start+6); no rejection |
| Displacement | horizontal ≥ 0.15; direction dot ≥ 0.90; `|dy| ≤ 0.20` |
| Freshness | a post-action observation with `state_tick >= ended` |

## Results

Vanilla minimized limit (10 FPS), two 30 s runs:

| Run | Observations | FPS | p50 | p95 | Max |
|---|---|---|---|---|---|
| 1 | 119 | 4.10 | 200.7 ms | 300.1 ms | 300.7 ms |
| 2 | 123 | 4.11 | 200.7 ms | 300.1 ms | 300.7 ms |

Both runs failed the 4.5 Hz gate. No observation-id gaps, no encoder failures,
and all frames were valid 854×480 JPEGs, but the 300 ms slips were too frequent.

A client mixin raises the minimized frame limit from 10 to 30 FPS
(`FramerateLimitTrackerMixin`); normal-window rendering, AFK limits and every
other throttle reason are unchanged. Two 30 s runs after the change:

| Run | Observations | FPS | p50 | p95 | Max | Forward distance | Dot | dy |
|---|---|---|---|---|---|---|---|---|
| 1 | 145 | 4.83 | 200.7 ms | 233.8 ms | 234.2 ms | 1.254 | 1.000 | 0 |
| 2 | 145 | 4.85 | 200.6 ms | 233.7 ms | 234.8 ms | 1.157 | 1.000 | 0 |

Both runs passed every check. The forward receipts were
`received → applied(start) → ended(start+6)`; `State.held.keys` contained 87
during the action; the player stayed on the ground; a post-action observation
arrived; and the integrated server saved and exited after `Shutdown`.

The saved minimized frames show the HUD and the world, not a black or frozen
image.

## Notes and limits

- 4.83–4.85 Hz is slightly below the nominal 5 Hz because a 30 FPS render loop
  still quantizes a 200 ms sample to 6 or 7 frames (200/233 ms). It is within the
  5 Hz ±20% band and above the 4.5 Hz gate.
- The check uses `Chat` for setup. This also exercises the chat/command path while
  minimized.
- The window was minimized programmatically with the Win32 `ShowWindow` call and
  verified with `IsIconic`; no manual minimize step is required.
- The normal-window runs were observed with the client unfocused and occluded by
  other application windows; their functional checks passed. No separate
  cadence comparison was made for that condition. Minimized is the harder case
  for capture because it also lowers the render FPS; the injected-input path is
  shared.
- Continuous mining is not part of this check; it has an independent
  `MouseHandler.isMouseGrabbed()` gate and is covered by the next step.
