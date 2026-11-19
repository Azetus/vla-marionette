package com.vlamarionette.marionette.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds the wire responses for queries and chat.
 *
 * <p>Builders never add {@code session_id}; {@link
 * com.vlamarionette.marionette.net.Transport} stamps the connection session.
 */
public final class QueryResponses {

    private QueryResponses() {
    }

    public static JsonObject blockOk(int requestId, long tick, boolean crosshair,
            int x, int y, int z, String blockState, Map<String, String> properties,
            String blockEntity, Double distance, String face) {
        JsonObject json = base("QueryBlockResult", requestId, true, null, tick);
        json.addProperty("query", crosshair ? "crosshair" : "pos");

        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        json.add("pos", pos);

        json.addProperty("block_state", Objects.requireNonNull(blockState, "blockState"));

        JsonObject propertiesJson = new JsonObject();
        for (Map.Entry<String, String> entry : new TreeMap<>(properties).entrySet()) {
            propertiesJson.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("properties", propertiesJson);

        if (blockEntity == null) {
            json.add("block_entity", JsonNull.INSTANCE);
        } else {
            json.addProperty("block_entity", blockEntity);
        }

        if (crosshair) {
            if (distance != null) {
                json.addProperty("distance", round(distance));
            }
            if (face != null) {
                json.addProperty("face", face);
            }
        }
        return json;
    }

    public static JsonObject blockError(Integer requestId, long tick, String reason) {
        return base("QueryBlockResult", requestId, false, reason, tick);
    }

    public static JsonObject inventoryOk(int requestId, long tick, int selected,
            List<Slot> slots) {
        JsonObject json = base("QueryInventoryResult", requestId, true, null, tick);
        json.addProperty("selected", selected);

        JsonArray slotsJson = new JsonArray();
        slots.stream()
                .sorted(Comparator.comparingInt(Slot::slot))
                .forEach(slot -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("slot", slot.slot());
                    entry.addProperty("item", slot.item());
                    entry.addProperty("count", slot.count());
                    slotsJson.add(entry);
                });
        json.add("slots", slotsJson);
        return json;
    }

    public static JsonObject inventoryError(Integer requestId, long tick, String reason) {
        return base("QueryInventoryResult", requestId, false, reason, tick);
    }

    public static JsonObject settingsOk(int requestId, long tick, String gameMode,
            double sensitivity, boolean invertMouseX, boolean invertMouseY,
            boolean smoothCamera, String cameraType, boolean scoping, Fov fov,
            boolean hideGui, int guiScale, int guiWidth, int guiHeight,
            List<Keybind> keybinds) {
        JsonObject json = base("QuerySettingsResult", requestId, true, null, tick);
        if (gameMode == null) {
            json.add("gamemode", JsonNull.INSTANCE);
        } else {
            json.addProperty("gamemode", gameMode);
        }
        json.addProperty("sensitivity", sensitivity);
        json.addProperty("invert_mouse_x", invertMouseX);
        json.addProperty("invert_mouse_y", invertMouseY);
        json.addProperty("smooth_camera", smoothCamera);
        json.addProperty("camera_type", cameraType);
        json.addProperty("scoping", scoping);
        json.addProperty("fov", round(fov.vertical()));
        json.addProperty("fov_effect_scale", round(fov.effectScale()));
        json.addProperty("fov_mode", fov.mode());
        json.addProperty("hide_gui", hideGui);
        json.addProperty("gui_scale", guiScale);
        json.addProperty("gui_width", guiWidth);
        json.addProperty("gui_height", guiHeight);

        JsonObject binds = new JsonObject();
        keybinds.stream().sorted().forEach(bind -> {
            if (bind.code() == null) {
                binds.add(bind.name(), JsonNull.INSTANCE);
            } else {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", bind.type());
                entry.addProperty("code", bind.code());
                binds.add(bind.name(), entry);
            }
        });
        json.add("keybinds", binds);
        return json;
    }

    public static JsonObject settingsError(Integer requestId, long tick, String reason) {
        return base("QuerySettingsResult", requestId, false, reason, tick);
    }

    public static JsonObject chatAck(Integer requestId, boolean ok, String reason,
            String kind, long tick) {
        JsonObject json = base("ChatAck", requestId, ok, reason, tick);
        if (kind == null) {
            json.add("kind", JsonNull.INSTANCE);
        } else {
            json.addProperty("kind", kind);
        }
        return json;
    }

    private static JsonObject base(String type, Integer requestId, boolean ok,
            String reason, long tick) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (requestId == null) {
            json.add("request_id", JsonNull.INSTANCE);
        } else {
            json.addProperty("request_id", requestId);
        }
        json.addProperty("ok", ok);
        if (reason == null) {
            json.add("reason", JsonNull.INSTANCE);
        } else {
            json.addProperty("reason", reason);
        }
        json.addProperty("tick", tick);
        return json;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** One non-empty inventory slot. */
    public record Slot(int slot, String item, int count) {

        public Slot {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Current FOV facts: the vertical FOV used for the world projection, the
     * client's FOV effect scale, and the startup mode ({@code fixed} or
     * {@code vanilla}).
     */
    public record Fov(double vertical, double effectScale, String mode) {

        public Fov {
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** One current keybind. A null code means the mapping is unbound. */
    public record Keybind(String name, String type, Integer code)
            implements Comparable<Keybind> {

        public Keybind {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public int compareTo(Keybind other) {
            return name.compareTo(other.name);
        }
    }
}
