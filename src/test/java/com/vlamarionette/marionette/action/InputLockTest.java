package com.vlamarionette.marionette.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InputLockTest {

    @BeforeEach
    @AfterEach
    void reset() {
        InputLock.disarm();
        InputLock.setTakeoverHandler(() -> {
        });
    }

    @Test
    void armedLockBlocksPhysicalButNotInjected() {
        InputLock.arm();

        assertTrue(InputLock.isLocked());
        assertTrue(InputLock.shouldBlockPhysical(false));
        assertFalse(InputLock.shouldBlockPhysical(true));
        InputInjection.run(() -> assertFalse(InputLock.shouldBlockPhysical(InputInjection.active())));
    }

    @Test
    void takeoverAllowsPhysicalAndRearmsWhenScreenCloses() {
        InputLock.arm();
        InputLock.beginTakeover();

        assertTrue(InputLock.inManualTakeover());
        assertFalse(InputLock.isLocked());
        assertFalse(InputLock.shouldBlockPhysical(false));

        InputLock.onClientTick(true);
        assertTrue(InputLock.inManualTakeover());
        InputLock.onClientTick(false);
        assertFalse(InputLock.inManualTakeover());
        assertTrue(InputLock.shouldBlockPhysical(false));
    }

    @Test
    void disarmUnlocksAndClearsHeld() {
        InputLock.arm();
        InputLock.notePhysicalKey(87, 1);
        InputLock.notePhysicalButton(0, 1);
        assertTrue(InputLock.physicalKeys().contains(87));
        assertTrue(InputLock.physicalButtons().contains(0));

        InputLock.disarm();

        assertFalse(InputLock.isLocked());
        assertFalse(InputLock.shouldBlockPhysical(false));
        assertTrue(InputLock.physicalKeys().isEmpty());
        assertTrue(InputLock.physicalButtons().isEmpty());
    }

    @Test
    void takeoverHandlerRunsOncePerTakeover() {
        AtomicInteger calls = new AtomicInteger();
        InputLock.arm();
        InputLock.setTakeoverHandler(calls::incrementAndGet);

        InputLock.beginTakeover();
        InputLock.beginTakeover();

        assertEquals(1, calls.get());
        InputLock.onClientTick(false);
        InputLock.beginTakeover();
        assertEquals(2, calls.get());
    }

    @Test
    void physicalReleaseRemovesHeldCode() {
        InputLock.arm();
        InputLock.notePhysicalKey(87, 1);
        InputLock.notePhysicalKey(87, 0);
        InputLock.notePhysicalButton(0, 1);
        InputLock.notePhysicalButton(0, 0);

        assertTrue(InputLock.physicalKeys().isEmpty());
        assertTrue(InputLock.physicalButtons().isEmpty());
    }
}
