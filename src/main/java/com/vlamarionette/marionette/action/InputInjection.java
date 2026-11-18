package com.vlamarionette.marionette.action;

/**
 * Marks input calls made by the bridge.
 *
 * <p>The marker only exists on the client thread while the injector dispatches
 * an event. It lets the injection path bypass focus checks and, later, the
 * physical input lock without letting normal input through.
 */
public final class InputInjection {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private InputInjection() {
    }

    public static void run(Runnable body) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            body.run();
        } finally {
            ACTIVE.set(previous);
        }
    }

    public static boolean active() {
        return ACTIVE.get();
    }
}
