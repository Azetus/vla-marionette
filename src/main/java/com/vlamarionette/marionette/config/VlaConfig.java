package com.vlamarionette.marionette.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.vlamarionette.marionette.capture.CaptureConfig;
import com.vlamarionette.marionette.capture.CaptureMode;
import com.vlamarionette.marionette.mc.FovConfig;
import com.vlamarionette.marionette.mc.FovMode;
import com.vlamarionette.marionette.net.BridgeConfig;
import com.vlamarionette.marionette.state.StateConfig;
import com.vlamarionette.marionette.state.StateMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the client TOML configuration.
 *
 * <p>The file is read once at startup. The server host/port, capture size,
 * capture mode, state cadence and FOV are startup settings; a running client
 * must be restarted after changing them.
 */
public record VlaConfig(BridgeConfig bridge, CaptureConfig capture,
        StateConfig state, FovConfig fov) {

    public static final String FILE_NAME = "vla_marionette-client.toml";

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private static final String DEFAULT_FILE = """
            [bridge]
            host = "127.0.0.1"
            port = 25580
            retry_ms = 2000

            [capture]
            # Window, render target and JPEG output all use this size.
            width = 854
            height = 480
            # wall_clock or tick
            mode = "wall_clock"
            hz = 5.0
            tick_interval = 4

            [state]
            # wall_clock: report at hz; tick: every tick_interval client ticks.
            mode = "wall_clock"
            hz = 10.0
            tick_interval = 1

            [fov]
            # fixed: force the world projection to `vertical` degrees (30-110).
            # vanilla: keep Minecraft's own FOV behavior.
            mode = "fixed"
            vertical = 70
            """;

    public static VlaConfig load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_FILE);
                LOG.info("[vla] wrote default config {}", file);
            }
        } catch (IOException e) {
            LOG.warn("[vla] could not create default config {}", file, e);
        }

        BridgeConfig bridge = defaultBridge();
        CaptureConfig capture = defaultCapture();
        StateConfig state = defaultState();
        FovConfig fov = defaultFov();

        FileConfig toml = FileConfig.of(file, TomlFormat.instance());
        try {
            toml.load();
            try {
                bridge = readBridge(toml);
            } catch (RuntimeException e) {
                LOG.warn("[vla] invalid bridge config in {}, using defaults", file, e);
            }
            try {
                capture = readCapture(toml);
            } catch (RuntimeException e) {
                LOG.warn("[vla] invalid capture config in {}, using defaults", file, e);
            }
            try {
                state = readState(toml);
            } catch (RuntimeException e) {
                LOG.warn("[vla] invalid state config in {}, using defaults", file, e);
            }
            try {
                fov = readFov(toml);
            } catch (RuntimeException e) {
                LOG.warn("[vla] invalid fov config in {}, using defaults", file, e);
            }
        } catch (RuntimeException e) {
            LOG.warn("[vla] could not read config {}, using defaults", file, e);
        } finally {
            toml.close();
        }

        bridge = applySystemOverrides(bridge);
        fov = applyFovSystemOverrides(fov);
        return new VlaConfig(bridge, capture, state, fov);
    }

    private static BridgeConfig readBridge(FileConfig toml) {
        String host = toml.getOrElse("bridge.host", "127.0.0.1");
        int port = toml.getOrElse("bridge.port", 25580);
        int retry = toml.getOrElse("bridge.retry_ms", 2000);
        return new BridgeConfig(host, port, retry);
    }

    private static CaptureConfig readCapture(FileConfig toml) {
        int width = toml.getOrElse("capture.width", 854);
        int height = toml.getOrElse("capture.height", 480);
        String modeValue = toml.get("capture.mode");
        CaptureMode mode = CaptureMode.parse(modeValue, CaptureMode.WALL_CLOCK);
        if (modeValue != null && mode == CaptureMode.WALL_CLOCK
                && !modeValue.toLowerCase(java.util.Locale.ROOT).startsWith("wall")) {
            LOG.warn("[vla] unknown capture.mode '{}', using wall_clock", modeValue);
        }
        Number hzValue = toml.get("capture.hz");
        double hz = hzValue == null ? 5.0 : hzValue.doubleValue();
        int tickInterval = toml.getOrElse("capture.tick_interval", 4);
        return new CaptureConfig(width, height, mode, hz, tickInterval);
    }

    /** Reads the independent {@code [state]} section. */
    private static StateConfig readState(FileConfig toml) {
        Object rawMode = toml.get("state.mode");
        Object rawHz = toml.get("state.hz");
        Object rawTick = toml.get("state.tick_interval");
        if (rawMode == null && rawHz == null && rawTick == null) {
            return StateConfig.DEFAULT;
        }
        StateMode mode = rawMode instanceof String modeValue
                ? StateMode.parse(modeValue, null)
                : null;
        if (mode == null) {
            LOG.warn("[vla] unknown state.mode, using defaults");
            return StateConfig.DEFAULT;
        }
        double hz = StateConfig.DEFAULT_HZ;
        if (rawHz != null) {
            if (!(rawHz instanceof Number number)) {
                LOG.warn("[vla] state.hz is not a number, using defaults");
                return StateConfig.DEFAULT;
            }
            hz = number.doubleValue();
        }
        int tickInterval = StateConfig.DEFAULT_TICK_INTERVAL;
        if (rawTick != null) {
            if (!(rawTick instanceof Number number)) {
                LOG.warn("[vla] state.tick_interval is not a number, using defaults");
                return StateConfig.DEFAULT;
            }
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value)
                    || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                LOG.warn("[vla] state.tick_interval is not an integer, using defaults");
                return StateConfig.DEFAULT;
            }
            tickInterval = (int) value;
        }
        try {
            return new StateConfig(mode, hz, tickInterval);
        } catch (RuntimeException e) {
            LOG.warn("[vla] invalid state config, using defaults", e);
            return StateConfig.DEFAULT;
        }
    }

    /**
     * Reads the independent {@code [fov]} section.
     *
     * <p>A missing section uses the device default (fixed 70). A present but
     * invalid section falls back to {@link FovMode#VANILLA}: a typo must not
     * silently force a different FOV. This is deliberately independent of the
     * capture section so an invalid FOV never resets the capture size.
     */
    private static FovConfig readFov(FileConfig toml) {
        Object rawMode = toml.get("fov.mode");
        Object rawVertical = toml.get("fov.vertical");
        if (rawMode == null && rawVertical == null) {
            return FovConfig.DEFAULT;
        }
        if (!(rawMode instanceof String modeValue)) {
            LOG.warn("[vla] fov.mode is missing or not a string, using vanilla");
            return FovConfig.VANILLA;
        }
        FovMode mode = FovMode.parse(modeValue, null);
        if (mode == null) {
            LOG.warn("[vla] unknown fov.mode '{}', using vanilla", modeValue);
            return FovConfig.VANILLA;
        }
        double vertical = FovConfig.DEFAULT_VERTICAL;
        if (rawVertical != null) {
            if (!(rawVertical instanceof Number number)) {
                LOG.warn("[vla] fov.vertical is not a number, using vanilla");
                return FovConfig.VANILLA;
            }
            vertical = number.doubleValue();
            if (!Double.isFinite(vertical)) {
                LOG.warn("[vla] fov.vertical is not finite, using vanilla");
                return FovConfig.VANILLA;
            }
        }
        if (mode == FovMode.FIXED
                && (vertical < FovConfig.MIN_VERTICAL || vertical > FovConfig.MAX_VERTICAL)) {
            LOG.warn("[vla] fov.vertical {} is outside {}..{}, using vanilla",
                    vertical, FovConfig.MIN_VERTICAL, FovConfig.MAX_VERTICAL);
            return FovConfig.VANILLA;
        }
        return new FovConfig(mode, vertical);
    }

    private static BridgeConfig applySystemOverrides(BridgeConfig base) {
        String host = System.getProperty("vla.bridge.host", base.host());
        int port = Integer.getInteger("vla.bridge.port", base.port());
        int retry = Integer.getInteger("vla.bridge.retry_ms", base.retryMillis());
        try {
            return new BridgeConfig(host, port, retry);
        } catch (RuntimeException e) {
            LOG.warn("[vla] invalid bridge system property, using config values", e);
            return base;
        }
    }

    /**
     * Launch-time FOV override for tests and experiment launchers.
     *
     * <p>Properties are {@code vla.fov.mode} and {@code vla.fov.vertical}. An
     * invalid override is logged and ignored, keeping the TOML values.
     */
    private static FovConfig applyFovSystemOverrides(FovConfig base) {
        String modeValue = System.getProperty("vla.fov.mode");
        String verticalValue = System.getProperty("vla.fov.vertical");
        if (modeValue == null && verticalValue == null) {
            return base;
        }
        try {
            FovMode mode = modeValue == null
                    ? base.mode()
                    : FovMode.parse(modeValue, null);
            if (mode == null) {
                throw new IllegalArgumentException("unknown vla.fov.mode: " + modeValue);
            }
            double vertical = verticalValue == null
                    ? base.vertical()
                    : Double.parseDouble(verticalValue);
            FovConfig override = new FovConfig(mode, vertical);
            LOG.info("[vla] fov system override mode={} vertical={}",
                    override.mode().wireName(), override.vertical());
            return override;
        } catch (RuntimeException e) {
            LOG.warn("[vla] invalid fov system property, using config values", e);
            return base;
        }
    }

    private static BridgeConfig defaultBridge() {
        return new BridgeConfig("127.0.0.1", 25580, 2000);
    }

    private static CaptureConfig defaultCapture() {
        return new CaptureConfig(854, 480, CaptureMode.WALL_CLOCK, 5.0, 4);
    }

    private static StateConfig defaultState() {
        return StateConfig.DEFAULT;
    }

    private static FovConfig defaultFov() {
        return FovConfig.DEFAULT;
    }
}
