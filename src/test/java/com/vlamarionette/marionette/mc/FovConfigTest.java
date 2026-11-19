package com.vlamarionette.marionette.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FovConfigTest {

    @Test
    void acceptsFixedRange() {
        assertEquals(30.0, new FovConfig(FovMode.FIXED, 30.0).vertical());
        assertEquals(110.0, new FovConfig(FovMode.FIXED, 110.0).vertical());
        assertEquals(70.5, new FovConfig(FovMode.FIXED, 70.5).vertical());
    }

    @Test
    void rejectsFixedOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(FovMode.FIXED, 29.9));
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(FovMode.FIXED, 110.1));
    }

    @Test
    void rejectsNonFiniteVertical() {
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(FovMode.FIXED, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(FovMode.FIXED, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(FovMode.VANILLA, Double.NaN));
    }

    @Test
    void vanillaIgnoresOutOfRangeValue() {
        FovConfig config = new FovConfig(FovMode.VANILLA, 500.0);

        assertEquals(FovMode.VANILLA, config.mode());
        assertEquals(500.0, config.vertical());
    }

    @Test
    void rejectsNullMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new FovConfig(null, 70.0));
    }

    @Test
    void defaultIsFixedSeventy() {
        assertEquals(FovMode.FIXED, FovConfig.DEFAULT.mode());
        assertEquals(70.0, FovConfig.DEFAULT.vertical());
        assertEquals(FovMode.VANILLA, FovConfig.VANILLA.mode());
    }
}
