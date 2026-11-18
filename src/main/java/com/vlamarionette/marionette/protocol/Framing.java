package com.vlamarionette.marionette.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Wire framing for the mod-to-host link.
 *
 * <pre>
 *   u32 frameLen      # bytes remaining: 4 + headerLen + bodyLen
 *   u32 headerLen
 *   header            # UTF-8 JSON object, must contain "type"
 *   body              # payload (JPEG for Observation, empty otherwise)
 * </pre>
 *
 * <p>All integers are big-endian. The limits are deliberately generous for a
 * JPEG frame but bounded so a malformed peer cannot allocate unbounded memory.
 */
public final class Framing {

    public static final int MAX_HEADER_BYTES = 64 * 1024;
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private static final Gson GSON = new Gson();

    private Framing() {
    }

    /** A decoded wire frame. Body is never {@code null}. */
    public record Frame(JsonObject header, byte[] body) {
        public Frame {
            Objects.requireNonNull(header, "header");
            body = body == null ? new byte[0] : body;
        }

        public String type() {
            return header.has("type") ? header.get("type").getAsString() : "";
        }
    }

    /** Encode one frame into a byte array. */
    public static byte[] encode(JsonObject header, byte[] body) throws IOException {
        Objects.requireNonNull(header, "header");
        byte[] headerBytes = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
        if (headerBytes.length > MAX_HEADER_BYTES) {
            throw new IOException("header too large: " + headerBytes.length);
        }
        int bodyLen = body == null ? 0 : body.length;
        if (bodyLen > MAX_BODY_BYTES) {
            throw new IOException("body too large: " + bodyLen);
        }

        int frameLen = 4 + headerBytes.length + bodyLen;
        ByteBuffer buffer = ByteBuffer.allocate(4 + frameLen);
        buffer.putInt(frameLen);
        buffer.putInt(headerBytes.length);
        buffer.put(headerBytes);
        if (bodyLen > 0) {
            buffer.put(body);
        }
        return buffer.array();
    }

    /** Write one frame and flush it. */
    public static void write(OutputStream out, JsonObject header, byte[] body) throws IOException {
        out.write(encode(header, body));
        out.flush();
    }

    /** Read one frame, blocking until the complete frame is available. */
    public static Frame read(InputStream in) throws IOException {
        ByteBuffer prefix = ByteBuffer.wrap(readFully(in, 8));
        int frameLen = prefix.getInt();
        int headerLen = prefix.getInt();

        if (frameLen < 4) {
            throw new IOException("invalid frameLen: " + frameLen);
        }
        if (headerLen < 0 || headerLen > MAX_HEADER_BYTES) {
            throw new IOException("invalid headerLen: " + headerLen);
        }
        int bodyLen = frameLen - 4 - headerLen;
        if (bodyLen < 0) {
            throw new IOException("headerLen exceeds frameLen: " + headerLen + " > " + frameLen);
        }
        if (bodyLen > MAX_BODY_BYTES) {
            throw new IOException("body too large: " + bodyLen);
        }

        byte[] headerBytes = readFully(in, headerLen);
        final JsonObject header;
        try {
            header = GSON.fromJson(new String(headerBytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            throw new IOException("invalid frame header JSON", e);
        }
        if (header == null) {
            throw new IOException("frame header is null");
        }

        byte[] body = bodyLen == 0 ? new byte[0] : readFully(in, bodyLen);
        return new Frame(header, body);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int got = in.read(data, read, length - read);
            if (got < 0) {
                throw new EOFException("connection closed after " + read + " of " + length + " bytes");
            }
            read += got;
        }
        return data;
    }
}
