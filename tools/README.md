# tools

Build, deployment, launch and acceptance scripts. Most Node scripts are bridge
hosts: they listen on the bridge port, so start the client (or let it retry)
before running them.

## Build, deploy and launch

| Script | Purpose |
|---|---|
| `build.ps1` | Run `mise install` and the Gradle build. |
| `deploy.ps1` | Remove old `vla-marionette*` files from `mods/` and install the versioned jar. |
| `launch-mc.ps1` | Launch the installed or an isolated client, record the PID, and support `-Fov`/`-FovMode`. |
| `stop-mc.ps1` | Validate the PID, request a window close, and fall back to PID termination. |
| `mc-argv.mjs` | Build the classpath/JVM/game argv from the version JSON; called by `launch-mc.ps1`. |
| `mc.local.example.ps1` | Local path template; copy to `mc.local.ps1` (git-ignored). |

## Acceptance and diagnostics

| Script | Purpose |
|---|---|
| `m1-run.ps1` | Launch, run M1 acceptance N times, stop the client and summarize. |
| `m1-acceptance.mjs` | M1: handshake, JPEG, one W chunk, receipts, post-action frame and state. |
| `query-check.mjs` | QueryBlock, QueryInventory, QuerySettings/FOV and Chat checks. |
| `fov-check.mjs` | FOV settings, frame capture, sprint and optional spyglass checks. |
| `matrix-check.mjs` | Action matrix; foreground or `--minimized`. |
| `background-check.mjs` | Minimized 30 s capture cadence and forward. |
| `dig-check.mjs` | Continuous dirt/stone mining with chained chunks. |
| `modifier-check.mjs` | Injected Ctrl modifier: Ctrl+Q vs Q. |
| `takeover-check.mjs` | Manual ESC takeover receipts (the operator presses ESC). |
| `input-lock-observe.mjs` | Manual observation of `input_locked`/`held`/`screen`. |

See each script's header for flags such as `--port`, `--shutdown` and
`--minimized`.
