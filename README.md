# VLA-Marionette

A NeoForge client-side mod for Minecraft 26.1.2 (NeoForge 26.1.2.126).

VLA-Marionette is a **device layer**: it exposes the game client to an external
controller through frame capture, state reporting, tick-level control injection,
and action receipts. It does not contain model prompts, action-text parsing, or
model-side conversion constants.

## Status

G1 through G5 are complete. The repository contains:

- Gradle/NeoForge build setup and wrapper.
- A client entry point and TCP transport.
- SessionInfo handshake, per-connection session ids, Ping/Pong, and reconnect.
- Fixed-size window and render target, main-target capture, JPEG encoding, and
  wall-clock 5 Hz sampling with HUD kept.
- A periodic state report with world/position/health/screen and capture status,
  including explicit no-world reasons.
- Raw per-tick input frames (GLFW keyboard/mouse codes plus client-area mouse
  pixels), action scheduling, and received/applied/ended receipts.
- Mod metadata and input/render mixins.

Build, unit tests, dev-client load, a real handshake, frame capture, state
sampling, a real forward action, and a GUI raw-input smoke have been verified.
The M1 acceptance harness and three-run acceptance are not implemented yet.

## Requirements

- [mise](https://mise.jdx.dev/) is used to pin the project JDK.
- The Gradle wrapper in this repository.
- A JDK 25 installation provided by `mise install`.

`mise.toml` currently pins Java 25.0.5.

## Build

```powershell
mise install
mise exec -- .\gradlew.bat build --console=plain
```

On Unix-like shells:

```sh
mise install
mise exec -- ./gradlew build --console=plain
```

If your Gradle cache is not the default `~/.gradle`, set `GRADLE_USER_HOME`
before building. The wrapper and dependencies are then resolved from that cache.

The build output is `build/libs/vla-marionette.jar`.

Run the pure unit tests with:

```powershell
mise exec -- .\gradlew.bat test --console=plain
```

## Layout

```
src/main/java/com/vlamarionette/marionette/
  protocol/       wire framing and SessionInfo
  net/            transport, connection lifecycle, inbound routing
  capture/        frame grabber, JPEG encoder, latest-frame mailbox, scheduler
  state/          client tick clock and state report
  action/         raw frames, scheduler, receipts, vanilla input injection
  mc/             window sizing
  mixin/          render-target, focus bypass, vanilla input accessors
src/main/resources/META-INF/neoforge.mods.toml
src/main/resources/vla_marionette.mixins.json
src/test/java/    pure unit tests (no game process)
tools/build.ps1
tools/g3-capture-check.mjs
tools/g4-state-check.mjs
tools/g5-action-check.mjs
tools/g5-gui-check.mjs
tools/mc.local.example.ps1
mise.toml
```

## License

MIT. See [LICENSE](LICENSE).
