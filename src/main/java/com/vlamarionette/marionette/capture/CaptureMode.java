package com.vlamarionette.marionette.capture;

/** How the capture scheduler decides when to sample. */
public enum CaptureMode {
    WALL_CLOCK,
    TICK_INTERVAL;

    public static CaptureMode parse(String value, CaptureMode fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "wall_clock", "wall-clock", "wallclock" -> WALL_CLOCK;
            case "tick", "tick_interval", "tick-interval" -> TICK_INTERVAL;
            default -> fallback;
        };
    }
}
