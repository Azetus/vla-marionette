package com.vlamarionette.marionette.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vlamarionette.marionette.capture.CaptureSpec;
import java.util.Objects;

/**
 * Builds the handshake message for one connection.
 *
 * <p>Capabilities list only what is actually available. The handshake has no
 * global version field; a host must use the advertised capabilities and field
 * shapes as the contract. Later milestones add raw input together with its
 * implementation.
 */
public final class SessionInfo {

    public static final String MC_VERSION = "26.1.2";
    public static final String MOD_VERSION = "0.1.0";
    public static final int TICK_MS = 50;

    private SessionInfo() {
    }

    public static JsonObject create(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        JsonObject info = new JsonObject();
        info.addProperty("type", "SessionInfo");
        info.addProperty("session_id", sessionId);
        info.addProperty("mc_version", MC_VERSION);
        info.addProperty("mod_version", MOD_VERSION);
        info.addProperty("tick_ms", TICK_MS);

        JsonObject image = new JsonObject();
        image.addProperty("width", CaptureSpec.width());
        image.addProperty("height", CaptureSpec.height());
        image.addProperty("pixel_format", "jpeg");
        image.addProperty("origin", "top-left");
        info.add("image", image);

        JsonObject render = new JsonObject();
        render.addProperty("width", CaptureSpec.width());
        render.addProperty("height", CaptureSpec.height());
        info.add("render", render);

        JsonObject window = new JsonObject();
        window.addProperty("width", CaptureSpec.width());
        window.addProperty("height", CaptureSpec.height());
        info.add("window", window);

        JsonArray capabilities = new JsonArray();
        capabilities.add("ping");
        capabilities.add("observation");
        capabilities.add("state");
        capabilities.add("raw_input");
        capabilities.add("shutdown");
        capabilities.add("query_block");
        capabilities.add("query_inventory");
        capabilities.add("chat");
        capabilities.add("query_settings");
        capabilities.add("set_hud");
        info.add("capabilities", capabilities);

        JsonObject limits = new JsonObject();
        limits.addProperty("max_action_frames", 20);
        info.add("limits", limits);

        return info;
    }
}
