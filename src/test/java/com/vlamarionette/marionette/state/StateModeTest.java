package com.vlamarionette.marionette.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StateModeTest {

    @Test
    void parsesNamesAndAliases() {
        assertEquals(StateMode.WALL_CLOCK, StateMode.parse("wall_clock", null));
        assertEquals(StateMode.WALL_CLOCK, StateMode.parse(" WALL-CLOCK ", null));
        assertEquals(StateMode.TICK_INTERVAL, StateMode.parse("tick", StateMode.WALL_CLOCK));
        assertEquals(StateMode.TICK_INTERVAL, StateMode.parse("tick_interval", null));
    }

    @Test
    void unknownAndMissingValuesUseFallback() {
        assertNull(StateMode.parse("nope", null));
        assertEquals(StateMode.WALL_CLOCK, StateMode.parse("nope", StateMode.WALL_CLOCK));
        assertNull(StateMode.parse(null, null));
        assertEquals(StateMode.TICK_INTERVAL, StateMode.parse(null, StateMode.TICK_INTERVAL));
    }

    @Test
    void wireNamesAreLowerCase() {
        assertEquals("wall_clock", StateMode.WALL_CLOCK.wireName());
        assertEquals("tick", StateMode.TICK_INTERVAL.wireName());
    }
}
