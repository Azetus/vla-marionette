package com.vlamarionette.marionette.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/**
 * A sequence of raw per-tick input frames.
 *
 * <p>The wire format deliberately has no {@code ticks} field: {@code
 * frames.size()} is the duration. A {@code null} frame is a no-op frame.
 */
public record ActionChunk(int seq, long startTick, List<ActionFrame> frames) {

    public static final int MAX_FRAMES = 20;

    public ActionChunk {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0");
        }
        if (startTick < 0) {
            throw new IllegalArgumentException("start_tick must be >= 0");
        }
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be empty");
        }
        frames = List.copyOf(frames);
    }

    public int endTickExclusive() {
        return Math.toIntExact(startTick + frames.size());
    }

    public static ActionChunk parse(JsonObject message) throws ActionParseException {
        Objects.requireNonNull(message, "message");
        int seq = requiredInt(message, "seq");
        long startTick = requiredLong(message, "start_tick");
        if (startTick < 0) {
            throw new ActionParseException("bad_shape", "start_tick must be >= 0");
        }

        JsonElement framesElement = message.get("frames");
        if (framesElement == null || !framesElement.isJsonArray()) {
            throw new ActionParseException("bad_shape", "frames must be an array");
        }
        JsonArray array = framesElement.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_FRAMES) {
            throw new ActionParseException("bad_frames",
                    "frames length must be 1.." + MAX_FRAMES);
        }

        List<ActionFrame> frames = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                frames.add(ActionFrame.EMPTY);
                continue;
            }
            if (!element.isJsonObject()) {
                throw new ActionParseException("bad_shape", "frame must be an object or null");
            }
            frames.add(parseFrame(element.getAsJsonObject()));
        }
        return new ActionChunk(seq, startTick, frames);
    }

    private static ActionFrame parseFrame(JsonObject frame) throws ActionParseException {
        Set<Integer> keys = readCodes(frame, "keys", 32, GLFW.GLFW_KEY_LAST);
        Set<Integer> buttons = readCodes(frame, "buttons", 0, GLFW.GLFW_MOUSE_BUTTON_LAST);

        double dx = 0.0;
        double dy = 0.0;
        double scroll = 0.0;
        JsonElement mouseElement = frame.get("mouse");
        if (mouseElement != null && !mouseElement.isJsonNull()) {
            if (!mouseElement.isJsonObject()) {
                throw new ActionParseException("bad_input", "mouse must be an object");
            }
            JsonObject mouse = mouseElement.getAsJsonObject();
            dx = optionalDouble(mouse, "dx");
            dy = optionalDouble(mouse, "dy");
            scroll = optionalDouble(mouse, "scroll");
        }
        return new ActionFrame(keys, buttons, dx, dy, scroll);
    }

    private static Set<Integer> readCodes(JsonObject frame, String name, int min, int max)
            throws ActionParseException {
        JsonElement element = frame.get(name);
        if (element == null || element.isJsonNull()) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            throw new ActionParseException("bad_input", name + " must be an array");
        }
        Set<Integer> codes = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new ActionParseException("bad_input", name + " must contain integers");
            }
            double raw = value.getAsDouble();
            int code = (int) raw;
            if (raw != code || code < min || code > max) {
                throw new ActionParseException("bad_input",
                        name + " code out of range: " + raw);
            }
            codes.add(code);
        }
        return codes;
    }

    private static double optionalDouble(JsonObject object, String name)
            throws ActionParseException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return 0.0;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ActionParseException("bad_input", "mouse." + name + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new ActionParseException("bad_input", "mouse." + name + " must be finite");
        }
        return value;
    }

    private static int requiredInt(JsonObject message, String name) throws ActionParseException {
        long value = requiredLong(message, name);
        if (value > Integer.MAX_VALUE) {
            throw new ActionParseException("bad_shape", name + " is too large");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject message, String name) throws ActionParseException {
        JsonElement element = message.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ActionParseException("bad_shape", "missing or invalid " + name);
        }
        double raw = element.getAsDouble();
        long value = (long) raw;
        if (!Double.isFinite(raw) || raw != value || value < 0 || value > Integer.MAX_VALUE) {
            throw new ActionParseException("bad_shape", "invalid " + name);
        }
        return value;
    }
}
