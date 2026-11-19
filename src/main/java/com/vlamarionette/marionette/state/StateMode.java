package com.vlamarionette.marionette.state;

import java.util.Locale;

/** How the periodic State report is paced. */
public enum StateMode {
    WALL_CLOCK("wall_clock"),
    TICK_INTERVAL("tick");

    private final String wireName;

    StateMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * Parses a config value.
     *
     * @param value    raw value; {@code null} returns {@code fallback}
     * @param fallback returned for a missing or unknown value
     */
    public static StateMode parse(String value, StateMode fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "wall_clock", "wall-clock", "wallclock" -> WALL_CLOCK;
            case "tick", "tick_interval", "tick-interval" -> TICK_INTERVAL;
            default -> fallback;
        };
    }
}
