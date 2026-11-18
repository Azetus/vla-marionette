package com.vlamarionette.marionette.capture;

/**
 * Capture constants for M1.
 *
 * <p>Render/window and encoded-output sizes are separate concepts: the game
 * renders at 640x360, the encoder scales to 854x480 for the model-facing JPEG.
 */
public final class CaptureSpec {

    public static final int RENDER_WIDTH = 640;
    public static final int RENDER_HEIGHT = 360;

    public static final int ENCODE_WIDTH = 854;
    public static final int ENCODE_HEIGHT = 480;
    public static final float JPEG_QUALITY = 0.9F;

    /** 5 Hz target, measured against wall clock. */
    public static final long FRAME_INTERVAL_NANOS = 200_000_000L;

    private CaptureSpec() {
    }
}
