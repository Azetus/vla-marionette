# VLA-Marionette

A NeoForge client-side mod for Minecraft 26.1.2 (NeoForge 26.1.2.126).

VLA-Marionette is a **device layer**. It exposes the game client to an external
controller through frame capture, state reporting, tick-level raw input
injection, and action receipts. It does not contain model prompts, action-text
parsing, model vocabulary, or model-side conversion constants.

## Status

The M1 loop is implemented and has passed a three-run acceptance on an installed
client:

- connection and `SessionInfo` handshake;
- 854×480 JPEG observations at a 5 Hz target;
- periodic state reports with actual held keys/buttons and input-lock status;
- raw per-tick keyboard/mouse frames with received/applied/ended receipts;
- block and inventory queries with per-request asynchronous replies;
- a unified chat/command entry (`Chat`), with no command filtering;
- a physical input lock with ESC manual takeover;
- reconnect as a new session;
- graceful host-initiated shutdown with integrated-server save.

## Documentation

- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [M1 acceptance](docs/m1-acceptance.md)
- [Query and chat acceptance](docs/query-acceptance.md)
- [Decisions](docs/decisions.md)

## Requirements

- [mise](https://mise.jdx.dev/) is used to pin the project JDK.
- The Gradle wrapper in this repository.
- A JDK 25 installation provided by `mise install`; `mise.toml` pins Java 25.0.5.
- A Minecraft/NeoForge client installation for live testing.
- A superflat test world for the M1 acceptance; the client must run in a window
  that the mod can size to 640×360.

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

# Run the acceptance host once, or three times with automatic shutdown.
.\tools\m1-run.ps1 -Installed -Runs 3 -World vla-m1
```

`launch-mc.ps1` can also create an isolated `runs/mc-*/game` copy without
touching the installed saves. `stop-mc.ps1` validates the process id and asks the
window to close first; forced PID termination is only a timeout fallback.

## Limitations

- There is no freeze, pause, or tick-rate override.
- The HUD is never hidden.
- While a host session is connected, physical keyboard/mouse input is suppressed
  except ESC. Keys or buttons held before the session connects are released when
  the lock arms, so they cannot keep acting. ESC releases injected input,
  interrupts the current action, and allows physical input while the pause
  screen is open; the lock re-arms when the screen closes.
- `State.held` reports actual raw held keys/buttons; `State.input_locked` reports
  whether physical input is currently suppressed.
- Block and inventory queries return client-side snapshots without NBT. Responses
  are correlated by `request_id` and may arrive out of order.
- `Chat` sends one line; a leading `/` uses the vanilla command path. There is no
  command filtering, and `ChatAck.ok` only means the line was dispatched, not
  that the server executed it.
- Background operation has not been fully verified: minimizing lowers vanilla
  rendering to 10 FPS, and continuous mining has an independent mouse-grab gate.
- There is no multi-chunk concurrency.
- A single client tick can contain at most one press/release edge per key or
  button; multiple edges inside one tick are not represented.

## Layout

```
src/main/java/com/vlamarionette/marionette/
  protocol/       wire framing and SessionInfo
  net/            transport, connection lifecycle, inbound routing, shutdown
  capture/        frame grabber, JPEG encoder, latest-frame mailbox, scheduler
  state/          client tick clock and state report
  action/         raw frames, scheduler, receipts, vanilla input injection
  query/          block/inventory reads and chat/command dispatch
  mc/             window sizing
  mixin/          render-target, focus bypass, vanilla input accessors
src/main/resources/META-INF/neoforge.mods.toml
src/main/resources/vla_marionette.mixins.json
src/test/java/    pure unit tests (no game process)
tools/            build, launcher, acceptance, and diagnostic scripts
docs/             public protocol and acceptance documentation
```

## License

MIT. See [LICENSE](LICENSE).
