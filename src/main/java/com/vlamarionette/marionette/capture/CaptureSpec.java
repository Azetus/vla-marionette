package com.vlamarionette.marionette.capture;

/**
 * Effective capture settings shared by the window lock, render-target mixin,
 * capture scheduler and encoder.
 *
 * <p>The values are configured once from the TOML file before the client starts
 * using them. The default is 854×480, matching the model-facing output.
 */
public final class CaptureSpec {

    public static final float JPEG_QUALITY = 0.9F;

    private static volatile CaptureConfig config =
            new CaptureConfig(854, 480, CaptureMode.WALL_CLOCK, 5.0, 4);

    private CaptureSpec() {
    }

    public static void configure(CaptureConfig newConfig) {
        config = newConfig;
    }

    public static CaptureConfig config() {
        return config;
    }

    public static int width() {
        return config.width();
    }

    public static int height() {
        return config.height();
    }

    public static CaptureMode mode() {
        return config.mode();
    }

    public static int tickInterval() {
        return config.tickInterval();
    }

    public static long frameIntervalNanos() {
        return Math.max(1L, Math.round(1_000_000_000.0 / config.wallClockHz()));
    }
}
