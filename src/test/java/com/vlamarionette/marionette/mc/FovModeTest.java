package com.vlamarionette.marionette.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FovModeTest {

    @Test
    void parsesNamesAndAliases() {
        assertEquals(FovMode.FIXED, FovMode.parse("fixed", null));
        assertEquals(FovMode.FIXED, FovMode.parse(" LOCK ", null));
        assertEquals(FovMode.VANILLA, FovMode.parse("vanilla", FovMode.FIXED));
        assertEquals(FovMode.VANILLA, FovMode.parse("auto", null));
        assertEquals(FovMode.VANILLA, FovMode.parse("off", FovMode.FIXED));
    }

    @Test
    void unknownAndMissingValuesUseFallback() {
        assertNull(FovMode.parse("nope", null));
        assertEquals(FovMode.FIXED, FovMode.parse("nope", FovMode.FIXED));
        assertNull(FovMode.parse(null, null));
        assertEquals(FovMode.VANILLA, FovMode.parse(null, FovMode.VANILLA));
    }

    @Test
    void wireNamesAreLowerCase() {
        assertEquals("fixed", FovMode.FIXED.wireName());
        assertEquals("vanilla", FovMode.VANILLA.wireName());
    }
}
