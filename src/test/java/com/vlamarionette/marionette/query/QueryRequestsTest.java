package com.vlamarionette.marionette.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class QueryRequestsTest {

    @Test
    void parsesBlockPositionQuery() throws Exception {
        QueryRequests.BlockQuery query = QueryRequests.parseBlock(json(
                "{\"type\":\"QueryBlock\",\"request_id\":7,\"pos\":[10,64,-3]}"));

        assertEquals(7, query.requestId());
        assertFalse(query.crosshair());
        assertEquals(new QueryRequests.Pos(10, 64, -3), query.pos());
    }

    @Test
    void missingPositionMeansCrosshair() throws Exception {
        QueryRequests.BlockQuery query = QueryRequests.parseBlock(json(
                "{\"type\":\"QueryBlock\",\"request_id\":8}"));

        assertEquals(8, query.requestId());
        assertTrue(query.crosshair());
        assertNull(query.pos());
    }

    @Test
    void nullPositionMeansCrosshair() throws Exception {
        QueryRequests.BlockQuery query = QueryRequests.parseBlock(json(
                "{\"type\":\"QueryBlock\",\"request_id\":9,\"pos\":null}"));

        assertTrue(query.crosshair());
    }

    @Test
    void missingRequestIdIsBadShapeWithoutCorrelation() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseBlock(json("{\"type\":\"QueryBlock\"}")));

        assertEquals("bad_shape", error.reason());
        assertNull(error.requestId());
    }

    @Test
    void rejectsPositionWithWrongLength() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseBlock(json(
                        "{\"type\":\"QueryBlock\",\"request_id\":10,\"pos\":[1,2]}")));

        assertEquals("bad_shape", error.reason());
        assertEquals(10, error.requestId().intValue());
    }

    @Test
    void rejectsNonNumericPosition() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseBlock(json(
                        "{\"type\":\"QueryBlock\",\"request_id\":11,\"pos\":[1,\"x\",3]}")));

        assertEquals("bad_shape", error.reason());
        assertEquals(11, error.requestId().intValue());
    }

    @Test
    void rejectsFractionalCoordinate() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseBlock(json(
                        "{\"type\":\"QueryBlock\",\"request_id\":12,\"pos\":[1,2.5,3]}")));

        assertEquals("bad_shape", error.reason());
        assertEquals(12, error.requestId().intValue());
    }

    @Test
    void parsesInventoryQuery() throws Exception {
        QueryRequests.InventoryQuery query = QueryRequests.parseInventory(json(
                "{\"type\":\"QueryInventory\",\"request_id\":13}"));

        assertEquals(13, query.requestId());
    }

    @Test
    void rejectsInventoryWithoutRequestId() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseInventory(json("{\"type\":\"QueryInventory\"}")));

        assertEquals("bad_shape", error.reason());
        assertNull(error.requestId());
    }

    @Test
    void parsesSettingsQuery() throws Exception {
        QueryRequests.SettingsQuery query = QueryRequests.parseSettings(json(
                "{\"type\":\"QuerySettings\",\"request_id\":14}"));

        assertEquals(14, query.requestId());
    }

    @Test
    void rejectsSettingsWithoutRequestId() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> QueryRequests.parseSettings(json("{\"type\":\"QuerySettings\"}")));

        assertEquals("bad_shape", error.reason());
        assertNull(error.requestId());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}


