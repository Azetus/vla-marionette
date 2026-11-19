# VLA-Marionette

A NeoForge client-side mod for Minecraft 26.1.2 (NeoForge 26.1.2.126).

VLA-Marionette is a **device layer**. It exposes the game client to an external
controller through frame capture, state reporting, tick-level raw input
injection, and action receipts. It does not contain model prompts, action-text
parsing, model vocabulary, or model-side conversion constants.

[中文 README](README.md)

## Features

- **Capture and state**: 854×480 JPEG at a 5 Hz default; 10 Hz state with
  position, rotation, health, ground state, hotbar, current screen, input-lock
  status, and the actual held keys/buttons.
- **Raw input**: per-tick GLFW key codes, mouse buttons 0–7, client-area mouse
  movement, and scroll; key combinations, held state, and actions continued
  across chunks are supported.
- **Action composition**: actions are composed from raw input.
- **Queries**: block, inventory, and client settings; the settings query returns
  the full current keybind table and `hide_gui`.
- **Chat/commands**: one `Chat` message per line; a leading `/` selects
  Minecraft's command path.
- **Session control**: physical input is locked while connected; ESC allows
  manual takeover; disconnect cleanup, reconnect as a new session, and graceful
  shutdown are supported.
- **HUD control**: the host can show/hide the HUD with `SetHud`, or toggle it
  with Minecraft's F1 key (show/hide HUD).
- **Minimized operation**: a minimized window keeps the 30 FPS render floor and
  raw input injection, with capture still targeting 5 Hz.

## Documentation

English:

- [English documentation](docs/en/README.md)
- [Architecture](docs/en/architecture.md)
- [Protocol](docs/en/protocol.md)
- [Actions](docs/en/actions.md)

Chinese:

- [中文文档索引](docs/zh/README.md)
- [中文 README](README.md)

## Requirements

- [mise](https://mise.jdx.dev/) is used to pin the project JDK.
- The Gradle wrapper in this repository.
- A JDK 25 installation provided by `mise install`; `mise.toml` pins Java 25.0.5.
- A Minecraft/NeoForge client installation for live testing.
- A superflat test world for live testing; the client window is configured,
  854×480 by default.

## Build and test

```powershell
mise install
mise exec -- .\gradlew.bat test --console=plain
mise exec -- .\gradlew.bat build --console=plain
```

On Unix-like shells:

```sh
mise install
mise exec -- ./gradlew test --console=plain
mise exec -- ./gradlew build --console=plain
```

The build output is `build/libs/vla-marionette.jar`. If the Gradle cache is not
the default `~/.gradle`, set `GRADLE_USER_HOME` before building.

## Configuration

The first launch creates `config/vla_marionette-client.toml`:

```toml
[bridge]
host = "127.0.0.1"
port = 25580
retry_ms = 2000

[capture]
width = 854
height = 480
mode = "wall_clock"   # wall_clock | tick
hz = 5.0
tick_interval = 4
```

- `width/height` set the window, render target and JPEG output together; all
  three are equal and no scaling is performed.
- `wall_clock` samples by real time; `tick` samples on the first render frame
  after every N client ticks.
- Port, size and capture-mode changes apply after a client restart; a running
  bridge does not reread the file.
- HUD is not in TOML; the host uses `SetHud`, and Minecraft's F1 key
  (show/hide HUD) remains an independent toggle.

## Live testing

The launcher scripts use local machine paths from an ignored configuration file.
Copy `tools/mc.local.example.ps1` to `tools/mc.local.ps1` and fill in:

- the installed version directory;
- the test world name;
- the bridge port;
- optionally a world source directory for an isolated copy.

The example file contains placeholders only.

Typical commands:

```powershell
# Deploy the built jar to an installation (old jar is kept as .jar.disabled).
.\tools\deploy.ps1 -GameDir '<version-dir>'

# Launch the installed client with a test world.
.\tools\launch-mc.ps1 -Installed -World vla-m1

# Run the test host once, or three times with automatic shutdown.
.\tools\m1-run.ps1 -Installed -Runs 3 -World vla-m1
```

`launch-mc.ps1` can also create an isolated `runs/mc-*/game` copy without
touching the installed saves. `stop-mc.ps1` validates the process id and asks the
window to close first; forced PID termination is only a timeout fallback.

## Limits

- **Device-layer scope**: no freeze, pause, or tick-rate override; the HUD is
  not hidden by default and the host can control it with `SetHud`; the wire
  carries raw input only, with no `forward`/`attack`/`camera` semantic fields.
- **Input scope**: GLFW key codes 32–348 and mouse buttons 0–7 are supported;
  GUI text input, Fn/media keys and mouse buttons beyond 0–7 are not.
- **Single-tick expression**: each key/button has at most one press/release edge
  per client tick; there is no sub-tick event stream.
- **Action scheduling**: one active plus at most one scheduled chunk; no
  multi-chunk concurrency.
- **Query scope**: client-side snapshots without NBT/components; responses are
  correlated by `request_id` and may arrive out of order; no entity or general
  world queries.
- **Session behavior**: physical input is locked while connected; keys held
  before the session are released when the lock arms; ESC releases injected input
  and allows manual takeover (physical input works while the pause screen is
  open). `ChatAck.ok` means dispatched, not executed by the server.
- **Environment**: minimized windows render at 30 FPS to keep 5 Hz capture;
  multiplayer servers and long runs are out of scope; tool mining, drops and
  entity interaction are Minecraft's behavior, with the device layer injecting raw
  input only.

## Layout

```text
src/main/java/com/vlamarionette/marionette/
  protocol/       wire framing and SessionInfo
  net/            transport, connection lifecycle, inbound routing, shutdown
  capture/        frame grabber, JPEG encoder, latest-frame mailbox, scheduler
  state/          client tick clock and state report
  action/         raw frames, scheduler, receipts, Minecraft's input injection
  query/          block/inventory reads and chat/command dispatch
  config/         TOML configuration loading
  mc/             window sizing and HUD control
  mixin/          render-target, focus bypass, Minecraft's input accessors
src/main/resources/META-INF/neoforge.mods.toml
src/main/resources/vla_marionette.mixins.json
src/test/java/    pure unit tests (no game process)
tools/            build, launcher, test, and diagnostic scripts
docs/en/          English technical documentation
docs/zh/          Chinese technical documentation
```

## License

MIT. See [LICENSE](LICENSE).
