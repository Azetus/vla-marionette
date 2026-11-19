package com.vlamarionette.marionette.net;

import java.util.Objects;

/**
 * Connection settings for the mod-to-host link.
 *
 * <p>Values come from the client TOML configuration. The bridge host and port
 * are startup settings; changing them requires a client restart.
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
}
