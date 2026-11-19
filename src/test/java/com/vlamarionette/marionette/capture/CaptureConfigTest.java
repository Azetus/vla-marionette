package com.vlamarionette.marionette.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CaptureConfigTest {

    @Test
    void parsesModeAliases() {
        assertEquals(CaptureMode.WALL_CLOCK, CaptureMode.parse("wall_clock", CaptureMode.TICK_INTERVAL));
        assertEquals(CaptureMode.WALL_CLOCK, CaptureMode.parse("wall-clock", CaptureMode.TICK_INTERVAL));
        assertEquals(CaptureMode.TICK_INTERVAL, CaptureMode.parse("tick", CaptureMode.WALL_CLOCK));
        assertEquals(CaptureMode.TICK_INTERVAL, CaptureMode.parse("tick_interval", CaptureMode.WALL_CLOCK));
    }

    @Test
    void rejectsInvalidDimensionsAndIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureConfig(
                0, 480, CaptureMode.WALL_CLOCK, 5.0, 4));
        assertThrows(IllegalArgumentException.class, () -> new CaptureConfig(
                854, 480, CaptureMode.WALL_CLOCK, 0.0, 4));
        assertThrows(IllegalArgumentException.class, () -> new CaptureConfig(
                854, 480, CaptureMode.TICK_INTERVAL, 5.0, 0));
    }
}
