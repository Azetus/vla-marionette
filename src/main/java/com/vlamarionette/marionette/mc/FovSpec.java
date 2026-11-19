package com.vlamarionette.marionette.mc;

/**
 * Effective FOV configuration for this client run.
 *
 * <p>FOV is an observation setting like the capture size: it is read once from
 * the startup config and does not have a runtime control channel. The renderer
 * reads this holder every frame; {@code QuerySettings} reads the same value.
 */
public final class FovSpec {

    private static volatile FovConfig current = FovConfig.DEFAULT;

    private FovSpec() {
    }

    public static void configure(FovConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("fov config is required");
        }
        current = config;
    }

    public static FovConfig config() {
        return current;
    }

    public static boolean fixed() {
        return current.mode() == FovMode.FIXED;
    }

    /** Fixed vertical FOV in degrees; only meaningful when {@link #fixed()}. */
    public static float fixedFov() {
        return (float) current.vertical();
    }
}
