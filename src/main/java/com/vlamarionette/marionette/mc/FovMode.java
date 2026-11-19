package com.vlamarionette.marionette.mc;

import java.util.Locale;

/** How the world projection FOV is chosen for this client run. */
public enum FovMode {
    FIXED("fixed"),
    VANILLA("vanilla");

    private final String wireName;

    FovMode(String wireName) {
        this.wireName = wireName;
    }

    /** Lower-case name used by the config and {@code QuerySettings.fov_mode}. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a config/property value.
     *
     * @param value    raw value; {@code null} returns {@code fallback}
     * @param fallback returned for a missing or unknown value
     */
    public static FovMode parse(String value, FovMode fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "fixed", "lock", "locked" -> FIXED;
            case "vanilla", "auto", "off" -> VANILLA;
            default -> fallback;
        };
    }
}
