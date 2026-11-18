package com.vlamarionette.marionette.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * Parses inbound {@code QueryBlock} / {@code QueryInventory} requests.
 *
 * <p>Shape validation runs on the network thread and never touches Minecraft
 * state. World bounds and chunk presence are checked later on the main thread.
 */
public final class QueryRequests {

    private QueryRequests() {
    }

    public static BlockQuery parseBlock(JsonObject message) throws QueryRequestException {
        Objects.requireNonNull(message, "message");
        int requestId = requestId(message);

        JsonElement posElement = message.get("pos");
        if (posElement == null || posElement.isJsonNull()) {
            return new BlockQuery(requestId, null);
        }
        if (!posElement.isJsonArray()) {
            throw new QueryRequestException(requestId, "bad_shape", "pos must be an array");
        }
        JsonArray array = posElement.getAsJsonArray();
        if (array.size() != 3) {
            throw new QueryRequestException(requestId, "bad_shape", "pos must have 3 coordinates");
        }
        int x = coordinate(array.get(0), requestId);
        int y = coordinate(array.get(1), requestId);
        int z = coordinate(array.get(2), requestId);
        return new BlockQuery(requestId, new Pos(x, y, z));
    }

    public static InventoryQuery parseInventory(JsonObject message) throws QueryRequestException {
        Objects.requireNonNull(message, "message");
        return new InventoryQuery(requestId(message));
    }

    static int requestId(JsonObject message) throws QueryRequestException {
        JsonElement element = message.get("request_id");
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new QueryRequestException(null, "bad_shape", "missing or invalid request_id");
        }
        double raw = element.getAsDouble();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new QueryRequestException(null, "bad_shape", "invalid request_id");
        }
        return (int) raw;
    }

    private static int coordinate(JsonElement element, int requestId)
            throws QueryRequestException {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new QueryRequestException(requestId, "bad_shape", "pos must contain numbers");
        }
        double raw = element.getAsDouble();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new QueryRequestException(requestId, "bad_shape",
                    "pos coordinate out of range");
        }
        return (int) raw;
    }

    public record Pos(int x, int y, int z) {
    }

    /** {@code pos == null} means "query the current crosshair target". */
    public record BlockQuery(int requestId, Pos pos) {

        public boolean crosshair() {
            return pos == null;
        }
    }

    public record InventoryQuery(int requestId) {
    }
}
