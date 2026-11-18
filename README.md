# VLA-Marionette

A NeoForge client-side mod for Minecraft 26.1.2 (NeoForge 26.1.2.126).

VLA-Marionette is a **device layer**: it exposes the game client to an external
controller through frame capture, state reporting, tick-level control injection,
and action receipts. It does not contain model prompts, action-text parsing, or
model-side conversion constants.

## Status

G1, G2, and G3 are complete. The repository contains:

- Gradle/NeoForge build setup and wrapper.
- A client entry point and TCP transport.
- SessionInfo handshake, per-connection session ids, Ping/Pong, and reconnect.
- Fixed-size window and render target, main-target capture, JPEG encoding, and
  wall-clock 5 Hz sampling with HUD kept.
- Mod metadata and a render-target mixin.

Build, unit tests, dev-client load, a real handshake, and real frame capture have
been verified. No state reporting or control injection is implemented yet. Do
not treat the current capture as a working M1 loop.

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
  state/          client tick clock
  mc/             window sizing
  mixin/          render-target resize
src/main/resources/META-INF/neoforge.mods.toml
src/main/resources/vla_marionette.mixins.json
src/test/java/    pure unit tests (no game process)
tools/build.ps1
tools/g3-capture-check.mjs
tools/mc.local.example.ps1
mise.toml
```

## License

MIT. See [LICENSE](LICENSE).
