package com.vlamarionette.marionette.state;

import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Single client-tick clock shared by capture and action scheduling.
 *
 * <p>Incremented at {@code ClientTickEvent.Pre}, before {@code handleKeybinds}
 * and player physics. Capture samples on the following render frame, so the
 * {@code state_tick} it records is the tick that has just completed.
 */
public final class ClientClock {

    private static volatile long tick;

    private ClientClock() {
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        tick++;
    }

    public static long tick() {
        return tick;
    }
}
