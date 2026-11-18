package com.vlamarionette.marionette.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SessionInfoTest {

    @Test
    void describesProtocolAndImageSpec() {
        JsonObject info = SessionInfo.create("session-1");

        assertEquals("SessionInfo", info.get("type").getAsString());
        assertEquals(1, info.get("protocol_version").getAsInt());
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
    void onlyAdvertisesImplementedG2Capabilities() {
        JsonObject info = SessionInfo.create("session-2");

        assertEquals(1, info.getAsJsonArray("capabilities").size());
        assertEquals("ping", info.getAsJsonArray("capabilities").get(0).getAsString());
        assertTrue(info.getAsJsonObject("controls").entrySet().isEmpty());
        assertEquals(20, info.getAsJsonObject("limits").get("max_chunk_ticks").getAsInt());
    }
}
