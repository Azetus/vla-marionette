package com.vlamarionette.marionette.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InjectedInputStateTest {

    @AfterEach
    void reset() {
        InjectedInputState.clear();
    }

    @Test
    void replacesTheWholeFrameState() {
        InjectedInputState.setHeld(Set.of(341, 81));

        assertTrue(InjectedInputState.isDown(341));
        assertTrue(InjectedInputState.isDown(81));

        InjectedInputState.setHeld(Set.of(81));

        assertFalse(InjectedInputState.isDown(341));
        assertTrue(InjectedInputState.isDown(81));
    }

    @Test
    void acceptsEitherLeftOrRightModifier() {
        InjectedInputState.setHeld(Set.of(345));

        assertTrue(InjectedInputState.isDown(341, 345));
        assertFalse(InjectedInputState.isDown(340, 344));
    }

    @Test
    void clearRemovesAllKeys() {
        InjectedInputState.setHeld(Set.of(340, 342));
        InjectedInputState.clear();

        assertFalse(InjectedInputState.isDown(340));
        assertFalse(InjectedInputState.isDown(342));
    }

    @Test
    void nullFrameIsEmpty() {
        InjectedInputState.setHeld(Set.of(87));
        InjectedInputState.setHeld(null);

        assertFalse(InjectedInputState.isDown(87));
    }

    @Test
    void keepsAnImmutableCopy() {
        Set<Integer> source = new HashSet<>(Set.of(87));
        InjectedInputState.setHeld(source);
        source.add(83);

        assertTrue(InjectedInputState.isDown(87));
        assertFalse(InjectedInputState.isDown(83));
    }
}
