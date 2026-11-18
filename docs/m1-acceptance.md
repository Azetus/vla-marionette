# M1 Acceptance

The M1 acceptance exercises the complete device loop:

1. connect and receive `SessionInfo`;
2. receive 854×480 JPEG observations;
3. wait for a stable in-world state;
4. send one raw-input chunk holding `W`;
5. verify `received`, `applied`, and `ended`;
6. verify post-action state and a post-action JPEG;
7. reconnect and repeat in a new session;
8. optionally request a graceful save/shutdown.

## Prerequisites

- JDK 25 installed through `mise`.
- Built jar at `build/libs/vla-marionette.jar`.
- A Minecraft 26.1.2 / NeoForge installation with a superflat test world.
- The test client window can be sized to 640×360.
- The host port is free (default 25580).

For strict acceptance, use a newly created superflat world with:

- peaceful difficulty;
- commands enabled;
- no structures or terrain steps in front of spawn;
- a world name configured as the test world.

The acceptance default vertical threshold is `|dy| <= 0.20`.

## Local configuration

Copy the tracked example:

```text
tools/mc.local.example.ps1  ->  tools/mc.local.ps1
```

Fill in local values. The actual file is ignored by git; the example contains
placeholders only.

Relevant variables:

| Variable | Purpose |
|---|---|
| `VLA_MC_VERSION_DIR` | installed version directory |
| `VLA_MC_WORLD` | test world folder/name |
| `VLA_MC_PORT` | bridge port |
| `VLA_MC_JAVA` | optional explicit `java.exe` |
| `VLA_MC_WORLD_SOURCE` | optional source save for isolated copies |
| `VLA_MC_ASSETS_DIR` | optional assets directory override |
| `VLA_MC_LIBRARIES_DIR` | optional libraries directory override |

## Deploy

```powershell
mise exec -- .\gradlew.bat test build --console=plain
.\tools\deploy.ps1 -GameDir '<version-dir>'
```

`deploy.ps1` removes the active `vla-marionette.jar`, keeps the previous one as
`.jar.disabled`, and requires exactly one active mod jar. The `.disabled` suffix
prevents NeoForge from loading the backup.

## Launch

Installed client (uses the installation's own `saves`/`options.txt`):

```powershell
.\tools\launch-mc.ps1 -Installed -World vla-m1 -RunDir '<run-dir>'
```

Isolated client (copies the world into a fresh game directory):

```powershell
.\tools\launch-mc.ps1 -World vla-m1 -RunDir '<run-dir>'
```

The launcher:

- resolves Java 25 through `mise`;
- builds the classpath from the version JSON;
- adds bridge host/port and a run id;
- starts with `--quickPlaySingleplayer <world>`;
- records the game PID and launch metadata;
- rejects a second concurrent test client.

## Run acceptance

One run:

```powershell
node tools/m1-acceptance.mjs --port 25580 --out '<output-dir>'
```

Three runs with a final graceful shutdown:

```powershell
.\tools\m1-run.ps1 -Installed -Runs 3 -World vla-m1 -MaxDy 0.20
```

`m1-run.ps1` launches one client and reuses it for all runs. Each acceptance run
closes its host socket; the mod reconnects for the next run, producing a new
session id. The last run appends `--shutdown`.

## Assertions

| Area | Assertion |
|---|---|
| Handshake | 854×480 JPEG spec; render/window 640×360; `raw_input` capability |
| First frame | valid JPEG body; dimensions parsed from SOF |
| State | in world, screen closed, on ground, capture valid |
| Action | `received` → `applied(start)` → `ended(start + frames.length)`; no rejection |
| Receipt order | `received` is not overtaken by `applied` |
| Physical lock | `input_locked` true while connected; held state reports raw keys |
| Post-action state | tick at or after `ended`; on ground; horizontal displacement ≥ 0.10 |
| Direction | dot product with pre-action forward ≥ 0.80 |
| Vertical | absolute dy ≤ `MaxDy` (default 0.20) |
| Post-action frame | `state_tick >= ended` and `obs_id` greater than first frame |
| Reconnect | each run creates a new session; no old action replay |
| Shutdown | `ShutdownAck`; integrated server saves; client exits |

Outputs per run:

- `report.json`;
- `first.jpg`;
- `after.jpg` (when available).

`m1-run.ps1` also writes `summary.json` with per-run exit codes and pass flags.

## Manual input-lock checks

Automated acceptance cannot press a physical key. Start the observer first:

```powershell
node tools/input-lock-observe.mjs
```

It prints changes to `input_locked`, `held`, and `screen` without sending actions.
Perform these once with the client focused:

1. Connect and wait in world. Press physical W: the player should **not** move;
   `State.input_locked` is `true` and `State.held.keys` contains the W code.
2. Hold physical W **before** the session connects, then connect while still
   holding it: movement must stop when the lock arms. Releasing and pressing W
   again must also produce no movement. This checks that pre-session held keys
   are released rather than staying stuck.
3. Inject a normal `W` chunk: the player moves; `State.held.keys` contains 87
   while the chunk is active.
4. Press physical ESC: the pause screen opens; an active/scheduled chunk receives
   `interrupted/manual_takeover`; `State.input_locked` is `false`.
5. In the pause screen, physical mouse/keyboard should work.
6. Try to send another `ActionChunk` while paused: it is rejected with
   `manual_takeover`.
7. Return to the game: the lock re-arms; a new chunk works.

## Stop behavior

- `m1-acceptance --shutdown` sends `Shutdown(save=true)` and waits for
  `ShutdownAck`.
- `stop-mc.ps1` validates the game PID/run id, sends `WM_CLOSE`, waits, and only
  then falls back to a PID-targeted forced termination.
- The scripts never kill Java processes by name.
- A missing main-window handle immediately after `ShutdownAck` is normal; the
  process is already in its close path.

## Troubleshooting

| Symptom | Check |
|---|---|
| No `SessionInfo` | host port, firewall, client log, bridge host/port properties |
| `late` receipts | choose a larger `start_tick` margin |
| Vertical threshold fails | use a flat test world or inspect terrain in front of spawn |
| No post-action JPEG | ensure capture is valid and wait for the next 5 Hz sample |
| Shutdown hangs | fall back to `stop-mc.ps1`, then check the integrated server log |
| Duplicate mod jars | ensure backups end in `.disabled`, not `.jar` |
