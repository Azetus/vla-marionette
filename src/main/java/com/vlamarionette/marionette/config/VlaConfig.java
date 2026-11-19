package com.vlamarionette.marionette.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.vlamarionette.marionette.capture.CaptureConfig;
import com.vlamarionette.marionette.capture.CaptureMode;
import com.vlamarionette.marionette.net.BridgeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the client TOML configuration.
 *
 * <p>The file is read once at startup. The server host/port, capture size and
 * capture mode are startup settings; a running client should be restarted (or
 * at least reconnected) after changing them.
 */
public record VlaConfig(BridgeConfig bridge, CaptureConfig capture) {

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
        } catch (RuntimeException e) {
            LOG.warn("[vla] could not read config {}, using defaults", file, e);
        } finally {
            toml.close();
        }

        bridge = applySystemOverrides(bridge);
        return new VlaConfig(bridge, capture);
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

    private static BridgeConfig defaultBridge() {
        return new BridgeConfig("127.0.0.1", 25580, 2000);
    }

    private static CaptureConfig defaultCapture() {
        return new CaptureConfig(854, 480, CaptureMode.WALL_CLOCK, 5.0, 4);
    }
}
