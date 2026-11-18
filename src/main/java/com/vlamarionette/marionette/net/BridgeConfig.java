package com.vlamarionette.marionette.net;

import java.util.Objects;

/**
 * Connection settings for the mod-to-host link.
 *
 * <p>G2 reads system properties so an isolated test game directory can select a
 * port without a config file. The property names are intentionally small and
 * machine-friendly:
 *
 * <pre>
 *   -Dvla.bridge.host=127.0.0.1
 *   -Dvla.bridge.port=25580
 *   -Dvla.bridge.retry_ms=2000
 * </pre>
 */
public record BridgeConfig(String host, int port, int retryMillis) {

    public BridgeConfig {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("bridge host is blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bridge port out of range: " + port);
        }
        if (retryMillis < 25) {
            throw new IllegalArgumentException("retryMillis must be >= 25: " + retryMillis);
        }
    }

    public static BridgeConfig fromSystemProperties() {
        String host = System.getProperty("vla.bridge.host", "127.0.0.1");
        int port = Integer.getInteger("vla.bridge.port", 25580);
        int retryMillis = Integer.getInteger("vla.bridge.retry_ms", 2000);
        return new BridgeConfig(host, port, retryMillis);
    }
}
