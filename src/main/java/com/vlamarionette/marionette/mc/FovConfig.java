package com.vlamarionette.marionette.mc;

/**
 * Startup FOV configuration.
 *
 * <p>{@code vertical} is the vertical field of view in degrees, matching
 * Minecraft's own FOV setting. It is only applied when {@code mode} is
 * {@link FovMode#FIXED}; {@link FovMode#VANILLA} leaves the client's own
 * behavior (including its dynamic modifiers) untouched.
 */
public record FovConfig(FovMode mode, double vertical) {

    public static final double MIN_VERTICAL = 30.0;
    public static final double MAX_VERTICAL = 110.0;
    public static final double DEFAULT_VERTICAL = 70.0;

    /** Device default: a fixed 70-degree vertical FOV. */
    public static final FovConfig DEFAULT = new FovConfig(FovMode.FIXED, DEFAULT_VERTICAL);

    /** Safe fallback for a present but invalid config: do not force any FOV. */
    public static final FovConfig VANILLA = new FovConfig(FovMode.VANILLA, DEFAULT_VERTICAL);

    public FovConfig {
        if (mode == null) {
            throw new IllegalArgumentException("fov mode is required");
        }
        if (!Double.isFinite(vertical)) {
            throw new IllegalArgumentException("fov vertical must be finite: " + vertical);
        }
        if (mode == FovMode.FIXED
                && (vertical < MIN_VERTICAL || vertical > MAX_VERTICAL)) {
            throw new IllegalArgumentException("fov vertical must be "
                    + MIN_VERTICAL + ".." + MAX_VERTICAL + ": " + vertical);
        }
    }

    public boolean fixed() {
        return mode == FovMode.FIXED;
    }
}
