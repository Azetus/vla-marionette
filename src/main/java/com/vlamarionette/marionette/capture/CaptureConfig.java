package com.vlamarionette.marionette.capture;

/**
 * User-configurable capture settings.
 *
 * <p>Window, render target and JPEG output all use {@code width × height}; the
 * encoder does not rescale the image.
 */
public record CaptureConfig(int width, int height, CaptureMode mode,
        double wallClockHz, int tickInterval) {

    public CaptureConfig {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("capture size must be positive: " + width + "x" + height);
        }
        if (mode == null) {
            throw new IllegalArgumentException("capture mode is required");
        }
        if (!Double.isFinite(wallClockHz) || wallClockHz <= 0.0) {
            throw new IllegalArgumentException("capture hz must be positive: " + wallClockHz);
        }
        if (tickInterval < 1) {
            throw new IllegalArgumentException("tick interval must be >= 1: " + tickInterval);
        }
    }
}
