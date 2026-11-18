package com.vlamarionette.marionette.action;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Physical-input lock and manual-takeover latch.
 *
 * <p>Injected events carry {@link InputInjection} and always pass. Physical
 * keyboard/mouse events are dropped while the lock is armed, except ESC, which
 * enters manual takeover: the scheduler is interrupted, injected input is
 * released, and physical input is allowed while the pause screen is open.
 */
public final class InputLock {

    private static final Set<Integer> PHYSICAL_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> PHYSICAL_BUTTONS = ConcurrentHashMap.newKeySet();

    private static volatile boolean armed;
    private static volatile boolean takeover;
    private static volatile Runnable takeoverHandler = () -> {
    };

    private InputLock() {
    }

    public static void setTakeoverHandler(Runnable handler) {
        takeoverHandler = handler == null ? () -> {
        } : handler;
    }

    /** Arm the lock for a new session and clear stale physical state. */
    public static void arm() {
        PHYSICAL_KEYS.clear();
        PHYSICAL_BUTTONS.clear();
        takeover = false;
        armed = true;
    }

    /** Disarm on disconnect; the client becomes human-controllable again. */
    public static void disarm() {
        armed = false;
        takeover = false;
        PHYSICAL_KEYS.clear();
        PHYSICAL_BUTTONS.clear();
    }

    public static boolean isArmed() {
        return armed;
    }

    /** True while physical input is being suppressed. */
    public static boolean isLocked() {
        return armed && !takeover;
    }

    /** True while the user has escaped through the pause screen. */
    public static boolean inManualTakeover() {
        return armed && takeover;
    }

    public static boolean shouldBlockPhysical(boolean injection) {
        return !injection && armed && !takeover;
    }

    /** Re-arm after the user closes the pause screen. */
    public static void onClientTick(boolean screenOpen) {
        if (armed && takeover && !screenOpen) {
            takeover = false;
        }
    }

    /**
     * Called on the client thread for a physical ESC press with no screen open.
     * The callback interrupts the scheduler and releases injected input.
     */
    public static void beginTakeover() {
        if (!armed || takeover) {
            return;
        }
        takeover = true;
        takeoverHandler.run();
    }

    public static void notePhysicalKey(int key, int action) {
        if (action == 0) {
            PHYSICAL_KEYS.remove(key);
        } else {
            PHYSICAL_KEYS.add(key);
        }
    }

    public static void notePhysicalButton(int button, int action) {
        if (action == 0) {
            PHYSICAL_BUTTONS.remove(button);
        } else {
            PHYSICAL_BUTTONS.add(button);
        }
    }

    public static Set<Integer> physicalKeys() {
        return new TreeSet<>(PHYSICAL_KEYS);
    }

    public static Set<Integer> physicalButtons() {
        return new TreeSet<>(PHYSICAL_BUTTONS);
    }
}
