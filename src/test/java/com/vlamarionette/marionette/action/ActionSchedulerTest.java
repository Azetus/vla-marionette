package com.vlamarionette.marionette.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionSchedulerTest {

    private final RecordingSink sink = new RecordingSink();
    private final List<JsonObject> receipts = new ArrayList<>();
    private final ActionScheduler scheduler = new ActionScheduler(sink, receipt -> {
        receipts.add(receipt);
        sink.events.add("receipt:" + status(receipt));
    });

    @Test
    void acceptsAppliesAndEndsAfterLastFrame() {
        ActionChunk chunk = chunk(1, 10, frame(Set.of(87)), ActionFrame.EMPTY);

        assertTrue(scheduler.submit(chunk, 5).accepted());
        assertEquals("received", status(receipts.get(0)));

        scheduler.onPre(10);
        assertEquals("applied", status(receipts.get(1)));
        assertEquals(10, receipts.get(1).get("tick").getAsLong());
        assertEquals(1, sink.applied.size());

        scheduler.onPost(10);
        assertEquals(1, sink.flushes);
        assertEquals(2, receipts.size());

        scheduler.onPre(11);
        assertEquals(2, sink.applied.size());
        scheduler.onPost(11);

        assertEquals(3, receipts.size());
        assertEquals("ended", status(receipts.get(2)));
        assertEquals(12, receipts.get(2).get("tick").getAsLong());
        assertEquals(2, sink.flushes);
    }

    @Test
    void flushesMouseBeforeEnded() {
        ActionChunk chunk = chunk(1, 10, ActionFrame.EMPTY);
        scheduler.submit(chunk, 5);
        scheduler.onPre(10);
        scheduler.onPost(10);

        assertEquals("ended", status(receipts.get(receipts.size() - 1)));
        assertTrue(sink.events.indexOf("flush") < sink.events.size() - 1);
        assertTrue(sink.events.lastIndexOf("flush") < sink.events.lastIndexOf("receipt:ended"));
    }

    @Test
    void rejectsLateBusyAndDuplicateWithoutConsumingSeq() {
        assertEquals("late", scheduler.submit(chunk(1, 10, ActionFrame.EMPTY), 10).reason());

        assertTrue(scheduler.submit(chunk(2, 10, ActionFrame.EMPTY), 5).accepted());
        assertEquals("busy", scheduler.submit(chunk(3, 20, ActionFrame.EMPTY), 5).reason());
        assertEquals("duplicate", scheduler.submit(chunk(2, 20, ActionFrame.EMPTY), 5).reason());
    }

    @Test
    void allowsOneScheduledChunkWhileActive() {
        assertTrue(scheduler.submit(chunk(1, 10, ActionFrame.EMPTY), 5).accepted());
        scheduler.onPre(10);
        assertTrue(scheduler.submit(chunk(2, 12, ActionFrame.EMPTY), 10).accepted());
        assertEquals("busy", scheduler.submit(chunk(3, 14, ActionFrame.EMPTY), 10).reason());
    }

    @Test
    void releaseInterruptsActiveAndClearsInput() {
        assertTrue(scheduler.submit(chunk(1, 10, frame(Set.of(69))), 5).accepted());
        scheduler.onPre(10);

        scheduler.release(10, "release");

        assertEquals("interrupted", status(receipts.get(receipts.size() - 1)));
        assertEquals("release", receipts.get(receipts.size() - 1).get("reason").getAsString());
        assertEquals(1, sink.releases);
        assertFalse(scheduler.hasAction());
    }

    @Test
    void gapReleasesHeldInputAndNewSessionResetsSeq() {
        assertTrue(scheduler.submit(chunk(1, 10, frame(Set.of(87))), 5).accepted());
        scheduler.onPre(10);
        scheduler.onPost(10);
        scheduler.onPre(11);
        scheduler.onPost(11);

        int releasesBeforeGap = sink.releases;
        scheduler.onPre(12);
        assertEquals(releasesBeforeGap + 1, sink.releases);

        scheduler.newSession();
        assertTrue(scheduler.submit(chunk(1, 20, ActionFrame.EMPTY), 12).accepted());
    }

    private static String status(JsonObject receipt) {
        return receipt.get("status").getAsString();
    }

    private static ActionFrame frame(Set<Integer> keys) {
        return new ActionFrame(keys, Set.of(), 0.0, 0.0, 0.0);
    }

    private static ActionChunk chunk(int seq, long startTick, ActionFrame... frames) {
        return new ActionChunk(seq, startTick, List.of(frames));
    }

    private final class RecordingSink implements InputSink {

        private final List<ActionFrame> applied = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private int flushes;
        private int releases;

        @Override
        public void applyFrame(ActionFrame frame) {
            applied.add(frame);
            events.add("apply");
        }

        @Override
        public void flushMouse() {
            flushes++;
            events.add("flush");
        }

        @Override
        public void releaseAll() {
            releases++;
            events.add("release");
        }
    }
}
