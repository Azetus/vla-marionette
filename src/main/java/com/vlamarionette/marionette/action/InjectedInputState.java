package com.vlamarionette.marionette.action;

import java.util.Set;

/**
 * Client-thread view of the keys held by the current injected action frame.
 *
 * <p>Vanilla keybinds run through {@code KeyMapping}, but a few paths (notably
 * {@code Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()}) poll the
 * physical GLFW keyboard instead. Injected events never touch GLFW state, so
 * this set lets those paths see the modifiers that are actually held in the
 * current frame.
 *
 * <p>The set is replaced at the start of every injected frame and cleared by
 * {@link McInputInjector#releaseAll()}; it is never a session-wide latch.
 */
public final class InjectedInputState {

    private static volatile Set<Integer> held = Set.of();

    private InjectedInputState() {
    }

    public static void setHeld(Set<Integer> keys) {
        held = keys == null ? Set.of() : Set.copyOf(keys);
    }

    public static void clear() {
        held = Set.of();
    }

    public static boolean isDown(int... keys) {
        for (int key : keys) {
            if (held.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
