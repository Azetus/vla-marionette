package com.vlamarionette.marionette.action;

import java.util.Set;

/**
 * One client-tick slice of raw input.
 *
 * <p>Keys and buttons are held states, not events; the injector computes
 * press/release edges against the previous tick. Mouse values are raw
 * client-area logical pixels / scroll notches.
 */
public record ActionFrame(
        Set<Integer> keys,
        Set<Integer> buttons,
        double mouseDx,
        double mouseDy,
        double scroll) {

    public static final ActionFrame EMPTY = new ActionFrame(Set.of(), Set.of(), 0.0, 0.0, 0.0);

    public ActionFrame {
        keys = keys == null ? Set.of() : Set.copyOf(keys);
        buttons = buttons == null ? Set.of() : Set.copyOf(buttons);
        if (!Double.isFinite(mouseDx) || !Double.isFinite(mouseDy) || !Double.isFinite(scroll)) {
            throw new IllegalArgumentException("mouse values must be finite");
        }
    }

    public boolean hasMouse() {
        return mouseDx != 0.0 || mouseDy != 0.0 || scroll != 0.0;
    }
}
