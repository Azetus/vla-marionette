package com.vlamarionette.marionette.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void leadingSlashSelectsCommandPath() throws Exception {
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":1,\"text\":\"/setblock 10 64 -3 minecraft:stone\"}"));

        assertEquals(1, request.requestId());
        assertEquals("setblock 10 64 -3 minecraft:stone", request.body());
        assertEquals(ChatRequest.KIND_COMMAND, request.kind());
    }

    @Test
    void plainTextSelectsChatPath() throws Exception {
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":2,\"text\":\"hello there\"}"));

        assertEquals("hello there", request.body());
        assertEquals(ChatRequest.KIND_CHAT, request.kind());
    }

    @Test
    void trimsBothEnds() throws Exception {
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":3,\"text\":\"  /time set day  \"}"));

        assertEquals("time set day", request.body());
        assertEquals(ChatRequest.KIND_COMMAND, request.kind());
    }

    @Test
    void preservesInternalWhitespace() throws Exception {
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":4,\"text\":\"/tellraw @a {\\\"text\\\":\\\"a  b\\\"}\"}"));

        assertEquals("tellraw @a {\"text\":\"a  b\"}", request.body());
        assertEquals(ChatRequest.KIND_COMMAND, request.kind());
    }

    @Test
    void doubleSlashKeepsOneSlashInBody() throws Exception {
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":5,\"text\":\"//foo\"}"));

        assertEquals("/foo", request.body());
        assertEquals(ChatRequest.KIND_COMMAND, request.kind());
    }

    @Test
    void rejectsEmptyText() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json(
                        "{\"type\":\"Chat\",\"request_id\":6,\"text\":\"   \"}")));

        assertEquals("bad_text", error.reason());
        assertEquals(6, error.requestId().intValue());
    }

    @Test
    void rejectsBareSlash() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json(
                        "{\"type\":\"Chat\",\"request_id\":7,\"text\":\"/\"}")));

        assertEquals("bad_text", error.reason());
        assertEquals(7, error.requestId().intValue());
    }

    @Test
    void rejectsTextLongerThanLimit() {
        String text = "a".repeat(ChatRequest.MAX_LENGTH + 1);
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json(
                        "{\"type\":\"Chat\",\"request_id\":8,\"text\":\"" + text + "\"}")));

        assertEquals("bad_text", error.reason());
        assertEquals(8, error.requestId().intValue());
    }

    @Test
    void acceptsTextAtLimit() throws Exception {
        String text = "a".repeat(ChatRequest.MAX_LENGTH);
        ChatRequest request = ChatRequest.parse(json(
                "{\"type\":\"Chat\",\"request_id\":9,\"text\":\"" + text + "\"}"));

        assertEquals(ChatRequest.MAX_LENGTH, request.body().length());
    }

    @Test
    void rejectsControlCharacters() {
        for (String text : new String[]{"line\\nbreak", "tab\\there", "nul\\u0000here"}) {
            QueryRequestException error = assertThrows(QueryRequestException.class,
                    () -> ChatRequest.parse(json(
                            "{\"type\":\"Chat\",\"request_id\":10,\"text\":\"" + text + "\"}")));
            assertEquals("bad_text", error.reason());
        }
    }

    @Test
    void rejectsMissingText() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json("{\"type\":\"Chat\",\"request_id\":11}")));

        assertEquals("bad_shape", error.reason());
        assertEquals(11, error.requestId().intValue());
    }

    @Test
    void rejectsNonStringText() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json(
                        "{\"type\":\"Chat\",\"request_id\":12,\"text\":42}")));

        assertEquals("bad_shape", error.reason());
        assertEquals(12, error.requestId().intValue());
    }

    @Test
    void rejectsMissingRequestId() {
        QueryRequestException error = assertThrows(QueryRequestException.class,
                () -> ChatRequest.parse(json("{\"type\":\"Chat\",\"text\":\"hi\"}")));

        assertEquals("bad_shape", error.reason());
        assertNull(error.requestId());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}


