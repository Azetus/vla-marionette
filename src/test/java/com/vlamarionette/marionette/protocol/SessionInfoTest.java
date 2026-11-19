package com.vlamarionette.marionette.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SessionInfoTest {

    @Test
    void describesProtocolAndImageSpec() {
        JsonObject info = SessionInfo.create("session-1");

        assertEquals("SessionInfo", info.get("type").getAsString());
        assertEquals("session-1", info.get("session_id").getAsString());
        assertEquals(50, info.get("tick_ms").getAsInt());

        JsonObject image = info.getAsJsonObject("image");
        assertEquals(854, image.get("width").getAsInt());
        assertEquals(480, image.get("height").getAsInt());
        assertEquals("jpeg", image.get("pixel_format").getAsString());
        assertEquals("top-left", image.get("origin").getAsString());

        assertEquals(640, info.getAsJsonObject("render").get("width").getAsInt());
        assertEquals(360, info.getAsJsonObject("render").get("height").getAsInt());
        assertEquals(640, info.getAsJsonObject("window").get("width").getAsInt());
        assertEquals(360, info.getAsJsonObject("window").get("height").getAsInt());
    }

    @Test
    void advertisesOnlyImplementedCapabilities() {
        JsonObject info = SessionInfo.create("session-2");

        assertEquals(9, info.getAsJsonArray("capabilities").size());
        assertEquals("ping", info.getAsJsonArray("capabilities").get(0).getAsString());
        assertEquals("observation", info.getAsJsonArray("capabilities").get(1).getAsString());
        assertEquals("state", info.getAsJsonArray("capabilities").get(2).getAsString());
        assertEquals("raw_input", info.getAsJsonArray("capabilities").get(3).getAsString());
        assertEquals("shutdown", info.getAsJsonArray("capabilities").get(4).getAsString());
        assertEquals("query_block", info.getAsJsonArray("capabilities").get(5).getAsString());
        assertEquals("query_inventory", info.getAsJsonArray("capabilities").get(6).getAsString());
        assertEquals("chat", info.getAsJsonArray("capabilities").get(7).getAsString());
        assertEquals("query_settings", info.getAsJsonArray("capabilities").get(8).getAsString());
        assertFalse(info.has("controls"));
        assertEquals(20, info.getAsJsonObject("limits").get("max_action_frames").getAsInt());
    }
}
