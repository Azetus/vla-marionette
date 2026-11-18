package com.vlamarionette.marionette.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionChunkTest {

    @Test
    void parsesRawFramesAndNullNoOp() throws Exception {
        JsonObject message = new JsonObject();
        message.addProperty("seq", 7);
        message.addProperty("start_tick", 120);
        JsonArray frames = new JsonArray();

        JsonObject first = new JsonObject();
        JsonArray keys = new JsonArray();
        keys.add(69);
        first.add("keys", keys);
        frames.add(first);
        frames.add(com.google.gson.JsonNull.INSTANCE);

        JsonObject third = new JsonObject();
        JsonArray buttons = new JsonArray();
        buttons.add(0);
        third.add("buttons", buttons);
        JsonObject mouse = new JsonObject();
        mouse.addProperty("dx", 12.5);
        mouse.addProperty("dy", -4);
        third.add("mouse", mouse);
        frames.add(third);

        message.add("frames", frames);

        ActionChunk chunk = ActionChunk.parse(message);

        assertEquals(7, chunk.seq());
        assertEquals(120, chunk.startTick());
        assertEquals(3, chunk.frames().size());
        assertEquals(List.of(69), List.copyOf(chunk.frames().get(0).keys()));
        assertTrue(chunk.frames().get(0).buttons().isEmpty());
        assertEquals(ActionFrame.EMPTY, chunk.frames().get(1));
        assertEquals(List.of(0), List.copyOf(chunk.frames().get(2).buttons()));
        assertEquals(12.5, chunk.frames().get(2).mouseDx());
        assertEquals(-4.0, chunk.frames().get(2).mouseDy());
        assertEquals(123, chunk.endTickExclusive());
    }

    @Test
    void rejectsEmptyOrTooManyFrames() {
        assertReason("bad_frames", message(new JsonArray()));
        JsonArray many = new JsonArray();
        for (int i = 0; i < 21; i++) {
            many.add(com.google.gson.JsonNull.INSTANCE);
        }
        assertReason("bad_frames", message(many));
    }

    @Test
    void rejectsOutOfRangeCodesAndBadMouse() {
        JsonObject keyFrame = new JsonObject();
        JsonArray keys = new JsonArray();
        keys.add(0);
        keyFrame.add("keys", keys);
        assertReason("bad_input", message(frames(keyFrame)));

        JsonObject buttonFrame = new JsonObject();
        JsonArray buttons = new JsonArray();
        buttons.add(8);
        buttonFrame.add("buttons", buttons);
        assertReason("bad_input", message(frames(buttonFrame)));

        JsonObject mouseFrame = new JsonObject();
        JsonObject mouse = new JsonObject();
        mouse.addProperty("dx", "fast");
        mouseFrame.add("mouse", mouse);
        assertReason("bad_input", message(frames(mouseFrame)));
    }

    @Test
    void rejectsMissingShape() {
        JsonObject noFrames = new JsonObject();
        noFrames.addProperty("seq", 1);
        noFrames.addProperty("start_tick", 10);
        assertReason("bad_shape", noFrames);

        JsonObject stringTick = message(frames(new JsonObject()));
        stringTick.addProperty("seq", 1);
        stringTick.addProperty("start_tick", "10");
        assertReason("bad_shape", stringTick);
    }

    private static JsonObject message(JsonArray frames) {
        JsonObject message = new JsonObject();
        message.addProperty("seq", 1);
        message.addProperty("start_tick", 10);
        message.add("frames", frames);
        return message;
    }

    private static JsonArray frames(JsonObject frame) {
        JsonArray frames = new JsonArray();
        frames.add(frame);
        return frames;
    }

    private static void assertReason(String expected, JsonObject message) {
        ActionParseException error = assertThrows(ActionParseException.class,
                () -> ActionChunk.parse(message));
        assertEquals(expected, error.reason());
    }
}
