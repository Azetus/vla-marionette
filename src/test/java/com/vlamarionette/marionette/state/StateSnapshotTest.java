package com.vlamarionette.marionette.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.capture.CaptureStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateSnapshotTest {

    @Test
    void serializesInWorldState() {
        StateSnapshot snapshot = new StateSnapshot(
                123L,
                456L,
                true,
                "minecraft:overworld",
                0.5, 64.0, 0.5,
                90.0, 10.0,
                20.0F, true, true, 3,
                "InventoryScreen", true, true,
                Set.of(87, 340), Set.of(0),
                CaptureStatus.valid(7, 42, 1));

        JsonObject json = snapshot.toJson("s1");

        assertEquals("State", json.get("type").getAsString());
        assertEquals("s1", json.get("session_id").getAsString());
        assertEquals(123L, json.get("tick").getAsLong());
        assertEquals(456L, json.get("game_time").getAsLong());
        assertTrue(json.get("in_world").getAsBoolean());
        assertEquals("minecraft:overworld", json.get("dimension").getAsString());
        assertEquals(0.5, json.get("x").getAsDouble());
        assertEquals(64.0, json.get("y").getAsDouble());
        assertEquals(90.0, json.get("yaw").getAsDouble());
        assertEquals(20.0F, json.get("health").getAsFloat());
        assertTrue(json.get("alive").getAsBoolean());
        assertTrue(json.get("on_ground").getAsBoolean());
        assertEquals(3, json.get("hotbar").getAsInt());
        assertEquals("InventoryScreen", json.get("screen").getAsString());
        assertTrue(json.get("screen_open").getAsBoolean());
        assertTrue(json.get("input_locked").getAsBoolean());
        JsonObject held = json.getAsJsonObject("held");
        assertEquals(87, held.getAsJsonArray("keys").get(0).getAsInt());
        assertEquals(340, held.getAsJsonArray("keys").get(1).getAsInt());
        assertEquals(0, held.getAsJsonArray("buttons").get(0).getAsInt());

        JsonObject capture = json.getAsJsonObject("capture");
        assertTrue(capture.get("valid").getAsBoolean());
        assertTrue(capture.get("reason").isJsonNull());
        assertEquals(7L, capture.get("last_obs_id").getAsLong());
        assertEquals(42L, capture.get("last_frame_tick").getAsLong());
        assertEquals(1, capture.get("encode_failures").getAsInt());
    }

    @Test
    void serializesNoWorldStateWithReason() {
        StateSnapshot snapshot = new StateSnapshot(
                1L,
                null,
                false,
                null,
                null, null, null,
                null, null,
                null, null, null, null,
                "TitleScreen", true, false,
                Set.of(), Set.of(),
                CaptureStatus.invalid(CaptureStatus.NO_LEVEL, 0, 0, 2));

        JsonObject json = snapshot.toJson("s2");

        assertFalse(json.get("in_world").getAsBoolean());
        assertTrue(json.get("game_time").isJsonNull());
        assertTrue(json.get("dimension").isJsonNull());
        assertTrue(json.get("x").isJsonNull());
        assertTrue(json.get("health").isJsonNull());
        assertTrue(json.get("hotbar").isJsonNull());
        assertEquals("TitleScreen", json.get("screen").getAsString());
        JsonObject held = json.getAsJsonObject("held");
        assertEquals(0, held.getAsJsonArray("keys").size());
        assertEquals(0, held.getAsJsonArray("buttons").size());

        JsonObject capture = json.getAsJsonObject("capture");
        assertFalse(capture.get("valid").getAsBoolean());
        assertEquals("no_level", capture.get("reason").getAsString());
        assertTrue(capture.get("last_obs_id").isJsonNull());
        assertTrue(capture.get("last_frame_tick").isJsonNull());
        assertEquals(2, capture.get("encode_failures").getAsInt());
    }
}
