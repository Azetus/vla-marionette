package com.vlamarionette.marionette.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryResponsesTest {

    @Test
    void serializesPositionBlockResult() {
        JsonObject json = QueryResponses.blockOk(1, 1288, false, 10, 64, -3,
                "minecraft:oak_log",
                Map.of("axis", "y", "waterlogged", "false"),
                null, null, null);

        assertEquals("QueryBlockResult", json.get("type").getAsString());
        assertEquals(1, json.get("request_id").getAsInt());
        assertTrue(json.get("ok").getAsBoolean());
        assertTrue(json.get("reason").isJsonNull());
        assertEquals(1288, json.get("tick").getAsInt());
        assertEquals("pos", json.get("query").getAsString());

        JsonArray pos = json.getAsJsonArray("pos");
        assertEquals(10, pos.get(0).getAsInt());
        assertEquals(64, pos.get(1).getAsInt());
        assertEquals(-3, pos.get(2).getAsInt());

        assertEquals("minecraft:oak_log", json.get("block_state").getAsString());
        JsonObject properties = json.getAsJsonObject("properties");
        assertEquals(2, properties.size());
        Iterator<String> keys = properties.keySet().iterator();
        assertEquals("axis", keys.next());
        assertEquals("waterlogged", keys.next());
        assertEquals("y", properties.get("axis").getAsString());
        assertEquals("false", properties.get("waterlogged").getAsString());

        assertTrue(json.get("block_entity").isJsonNull());
        assertFalse(json.has("distance"));
        assertFalse(json.has("face"));
        assertFalse(json.has("session_id"));
    }

    @Test
    void serializesCrosshairBlockResult() {
        JsonObject json = QueryResponses.blockOk(2, 1290, true, 1, -59, 0,
                "minecraft:stone", Map.of(), "minecraft:chest", 2.5149, "east");

        assertEquals("crosshair", json.get("query").getAsString());
        assertEquals(2.515, json.get("distance").getAsDouble());
        assertEquals("east", json.get("face").getAsString());
        assertEquals("minecraft:chest", json.get("block_entity").getAsString());
        assertTrue(json.getAsJsonObject("properties").isEmpty());
    }

    @Test
    void serializesUncorrelatedBlockError() {
        JsonObject json = QueryResponses.blockError(null, 1291, "bad_shape");

        assertFalse(json.get("ok").getAsBoolean());
        assertTrue(json.get("request_id").isJsonNull());
        assertEquals("bad_shape", json.get("reason").getAsString());
        assertFalse(json.has("pos"));
        assertFalse(json.has("query"));
    }

    @Test
    void serializesInventoryResultSortedBySlot() {
        JsonObject json = QueryResponses.inventoryOk(4, 1300, 0, List.of(
                new QueryResponses.Slot(40, "minecraft:shield", 1),
                new QueryResponses.Slot(0, "minecraft:stone", 5)));

        assertEquals("QueryInventoryResult", json.get("type").getAsString());
        assertEquals(0, json.get("selected").getAsInt());

        JsonArray slots = json.getAsJsonArray("slots");
        assertEquals(2, slots.size());
        assertEquals(0, slots.get(0).getAsJsonObject().get("slot").getAsInt());
        assertEquals("minecraft:stone", slots.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(5, slots.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(40, slots.get(1).getAsJsonObject().get("slot").getAsInt());
    }

    @Test
    void serializesInventoryError() {
        JsonObject json = QueryResponses.inventoryError(5, 1301, "no_level");

        assertEquals("QueryInventoryResult", json.get("type").getAsString());
        assertFalse(json.get("ok").getAsBoolean());
        assertEquals("no_level", json.get("reason").getAsString());
        assertFalse(json.has("slots"));
    }

    @Test
    void serializesChatAck() {
        JsonObject ok = QueryResponses.chatAck(6, true, null, ChatRequest.KIND_COMMAND, 1305);
        assertEquals("ChatAck", ok.get("type").getAsString());
        assertTrue(ok.get("ok").getAsBoolean());
        assertTrue(ok.get("reason").isJsonNull());
        assertEquals("command", ok.get("kind").getAsString());
        assertEquals(1305, ok.get("tick").getAsInt());

        JsonObject failed = QueryResponses.chatAck(7, false, "bad_text", null, 1306);
        assertFalse(failed.get("ok").getAsBoolean());
        assertEquals("bad_text", failed.get("reason").getAsString());
        assertTrue(failed.get("kind").isJsonNull());
    }

    @Test
    void echoesLargeRequestId() {
        JsonObject json = QueryResponses.inventoryOk(Integer.MAX_VALUE, 1, 0, List.of());

        assertEquals(Integer.MAX_VALUE, json.get("request_id").getAsInt());
    }

    @Test
    void keepsReasonNullOnSuccess() {
        JsonObject json = QueryResponses.inventoryOk(8, 1, 3, List.of());

        assertTrue(json.get("reason").isJsonNull());
    }
}
