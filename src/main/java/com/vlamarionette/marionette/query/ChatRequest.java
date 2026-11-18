package com.vlamarionette.marionette.query;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * One normalized chat line.
 *
 * <p>A leading slash selects the command path, matching vanilla
 * {@code ChatScreen}: the slash is removed before dispatch. The mod does not
 * parse command names or filter the text.
 */
public record ChatRequest(int requestId, String body, String kind) {

    public static final String KIND_CHAT = "chat";
    public static final String KIND_COMMAND = "command";
    public static final int MAX_LENGTH = 256;

    public ChatRequest {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(kind, "kind");
    }

    public static ChatRequest parse(JsonObject message) throws QueryRequestException {
        Objects.requireNonNull(message, "message");
        int requestId = QueryRequests.requestId(message);

        JsonElement element = message.get("text");
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new QueryRequestException(requestId, "bad_shape", "missing or invalid text");
        }
        String raw = element.getAsString();
        if (raw.length() > MAX_LENGTH) {
            throw new QueryRequestException(requestId, "bad_text",
                    "text longer than " + MAX_LENGTH);
        }
        if (containsControlCharacter(raw)) {
            throw new QueryRequestException(requestId, "bad_text",
                    "text contains a control character");
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new QueryRequestException(requestId, "bad_text", "text is empty");
        }

        if (text.startsWith("/")) {
            String body = text.substring(1).strip();
            if (body.isEmpty()) {
                throw new QueryRequestException(requestId, "bad_text", "command is empty");
            }
            return new ChatRequest(requestId, body, KIND_COMMAND);
        }
        return new ChatRequest(requestId, text, KIND_CHAT);
    }

    private static boolean containsControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
