package com.vlamarionette.marionette.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FovSpecTest {

    @AfterEach
    void reset() {
        FovSpec.configure(FovConfig.DEFAULT);
    }

    @Test
    void fixedModeExposesTheConfiguredValue() {
        FovSpec.configure(new FovConfig(FovMode.FIXED, 90.5));

        assertTrue(FovSpec.fixed());
        assertEquals(90.5f, FovSpec.fixedFov());
        assertEquals(90.5, FovSpec.config().vertical());
    }

    @Test
    void vanillaModeHasNoFixedValue() {
        FovSpec.configure(new FovConfig(FovMode.VANILLA, 70.0));

        assertFalse(FovSpec.fixed());
        assertEquals(FovMode.VANILLA, FovSpec.config().mode());
    }

    @Test
    void defaultsToFixedSeventy() {
        FovSpec.configure(FovConfig.DEFAULT);

        assertTrue(FovSpec.fixed());
        assertEquals(70.0f, FovSpec.fixedFov());
    }
}
