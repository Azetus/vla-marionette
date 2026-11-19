package com.vlamarionette.marionette.state;

/**
 * Startup pacing for the periodic {@code State} report.
 *
 * <p>{@code wallClockHz} is used in {@link StateMode#WALL_CLOCK}; {@code
 * tickInterval} is used in {@link StateMode#TICK_INTERVAL}. Both values are
 * validated so an invalid section can fall back as a whole.
 */
public record StateConfig(StateMode mode, double wallClockHz, int tickInterval) {

    public static final double DEFAULT_HZ = 10.0;
    public static final int DEFAULT_TICK_INTERVAL = 1;

    public static final StateConfig DEFAULT = new StateConfig(
            StateMode.WALL_CLOCK, DEFAULT_HZ, DEFAULT_TICK_INTERVAL);

    public StateConfig {
        if (mode == null) {
            throw new IllegalArgumentException("state mode is required");
        }
        if (!Double.isFinite(wallClockHz) || wallClockHz <= 0.0) {
            throw new IllegalArgumentException(
                    "state hz must be positive: " + wallClockHz);
        }
        if (tickInterval < 1) {
            throw new IllegalArgumentException(
                    "state tick_interval must be >= 1: " + tickInterval);
        }
    }

    public boolean tickMode() {
        return mode == StateMode.TICK_INTERVAL;
    }

    /** Wall-clock interval in nanoseconds; only meaningful in wall_clock mode. */
    public long wallClockIntervalNanos() {
        return Math.max(1L, Math.round(1_000_000_000.0 / wallClockHz));
    }
}
