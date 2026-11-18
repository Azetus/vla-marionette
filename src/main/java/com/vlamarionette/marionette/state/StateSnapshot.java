package com.vlamarionette.marionette.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.vlamarionette.marionette.capture.CaptureStatus;
import java.util.List;
import java.util.Objects;

/**
 * Immutable state snapshot serialized as the {@code State} message.
 *
 * <p>Fields that do not exist outside a world (position, dimension, health,
 * ...) are boxed and serialized as JSON {@code null}. The capture block always
 * exists so the host can distinguish "not in world", "bad render size", and
 * "encoder failure" without guessing from missing observations.
 */
public record StateSnapshot(
        long tick,
        Long gameTime,
        boolean inWorld,
        String dimension,
        Double x,
        Double y,
        Double z,
        Double yaw,
        Double pitch,
        Float health,
        Boolean alive,
        Boolean onGround,
        Integer hotbar,
        String screen,
        boolean screenOpen,
        boolean inputLocked,
        List<String> held,
        CaptureStatus capture) {

    public StateSnapshot {
        held = held == null ? List.of() : List.copyOf(held);
        capture = Objects.requireNonNull(capture, "capture");
    }

    public JsonObject toJson(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        JsonObject json = new JsonObject();
        json.addProperty("type", "State");
        json.addProperty("session_id", sessionId);
        json.addProperty("tick", tick);
        putNullable(json, "game_time", gameTime);
        json.addProperty("in_world", inWorld);
        putNullable(json, "dimension", dimension);
        putNullable(json, "x", x);
        putNullable(json, "y", y);
        putNullable(json, "z", z);
        putNullable(json, "yaw", yaw);
        putNullable(json, "pitch", pitch);
        putNullable(json, "health", health);
        putNullable(json, "alive", alive);
        putNullable(json, "on_ground", onGround);
        putNullable(json, "hotbar", hotbar);
        putNullable(json, "screen", screen);
        json.addProperty("screen_open", screenOpen);
        json.addProperty("input_locked", inputLocked);

        JsonArray heldJson = new JsonArray();
        for (String control : held) {
            heldJson.add(control);
        }
        json.add("held", heldJson);

        JsonObject captureJson = new JsonObject();
        captureJson.addProperty("valid", capture.valid());
        putNullable(captureJson, "reason", capture.reason());
        if (capture.lastObsId() > 0) {
            captureJson.addProperty("last_obs_id", capture.lastObsId());
        } else {
            captureJson.add("last_obs_id", JsonNull.INSTANCE);
        }
        if (capture.lastFrameTick() > 0) {
            captureJson.addProperty("last_frame_tick", capture.lastFrameTick());
        } else {
            captureJson.add("last_frame_tick", JsonNull.INSTANCE);
        }
        captureJson.addProperty("encode_failures", capture.encodeFailures());
        json.add("capture", captureJson);

        return json;
    }

    private static void putNullable(JsonObject json, String key, Object value) {
        if (value == null) {
            json.add(key, JsonNull.INSTANCE);
        } else if (value instanceof Number number) {
            json.addProperty(key, number);
        } else if (value instanceof Boolean bool) {
            json.addProperty(key, bool);
        } else {
            json.addProperty(key, value.toString());
        }
    }
}
