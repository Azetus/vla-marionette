package com.vlamarionette.marionette.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * Builds the handshake message for one connection.
 *
 * <p>G2 only implements transport/session and Ping/Pong, so capabilities and
 * controls list only what is actually available. Later milestones add
 * observation, state, and action capabilities together with their
 * implementations.
 */
public final class SessionInfo {

    public static final int PROTOCOL_VERSION = 1;
    public static final String MC_VERSION = "26.1.2";
    public static final String MOD_VERSION = "0.1.0";
    public static final int TICK_MS = 50;

    public static final int IMAGE_WIDTH = 854;
    public static final int IMAGE_HEIGHT = 480;
    public static final int RENDER_WIDTH = 640;
    public static final int RENDER_HEIGHT = 360;
    public static final int WINDOW_WIDTH = 640;
    public static final int WINDOW_HEIGHT = 360;

    private SessionInfo() {
    }

    public static JsonObject create(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        JsonObject info = new JsonObject();
        info.addProperty("type", "SessionInfo");
        info.addProperty("protocol_version", PROTOCOL_VERSION);
        info.addProperty("session_id", sessionId);
        info.addProperty("mc_version", MC_VERSION);
        info.addProperty("mod_version", MOD_VERSION);
        info.addProperty("tick_ms", TICK_MS);

        JsonObject image = new JsonObject();
        image.addProperty("width", IMAGE_WIDTH);
        image.addProperty("height", IMAGE_HEIGHT);
        image.addProperty("pixel_format", "jpeg");
        image.addProperty("origin", "top-left");
        info.add("image", image);

        JsonObject render = new JsonObject();
        render.addProperty("width", RENDER_WIDTH);
        render.addProperty("height", RENDER_HEIGHT);
        info.add("render", render);

        JsonObject window = new JsonObject();
        window.addProperty("width", WINDOW_WIDTH);
        window.addProperty("height", WINDOW_HEIGHT);
        info.add("window", window);

        JsonArray capabilities = new JsonArray();
        capabilities.add("ping");
        capabilities.add("observation");
        info.add("capabilities", capabilities);

        info.add("controls", new JsonObject());

        JsonObject limits = new JsonObject();
        limits.addProperty("max_chunk_ticks", 20);
        info.add("limits", limits);

        return info;
    }
}
