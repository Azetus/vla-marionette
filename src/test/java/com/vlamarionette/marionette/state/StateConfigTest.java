package com.vlamarionette.marionette.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StateConfigTest {

    @Test
    void defaultIsWallClockTenHz() {
        assertEquals(StateMode.WALL_CLOCK, StateConfig.DEFAULT.mode());
        assertEquals(10.0, StateConfig.DEFAULT.wallClockHz());
        assertEquals(1, StateConfig.DEFAULT.tickInterval());
        assertFalse(StateConfig.DEFAULT.tickMode());
    }

    @Test
    void acceptsTickMode() {
        StateConfig config = new StateConfig(StateMode.TICK_INTERVAL, 5.0, 2);

        assertTrue(config.tickMode());
        assertEquals(StateMode.TICK_INTERVAL, config.mode());
        assertEquals(2, config.tickInterval());
    }

    @Test
    void convertsWallClockHzToNanos() {
        assertEquals(100_000_000L,
                new StateConfig(StateMode.WALL_CLOCK, 10.0, 1).wallClockIntervalNanos());
        assertEquals(200_000_000L,
                new StateConfig(StateMode.WALL_CLOCK, 5.0, 1).wallClockIntervalNanos());
        assertEquals(50_000_000L,
                new StateConfig(StateMode.WALL_CLOCK, 20.0, 1).wallClockIntervalNanos());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new StateConfig(null, 10.0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StateConfig(StateMode.WALL_CLOCK, 0.0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StateConfig(StateMode.WALL_CLOCK, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StateConfig(StateMode.TICK_INTERVAL, 10.0, 0));
    }
}
