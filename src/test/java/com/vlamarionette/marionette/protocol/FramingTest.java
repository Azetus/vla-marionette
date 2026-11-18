package com.vlamarionette.marionette.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class FramingTest {

    @Test
    void roundTripsHeaderAndBody() throws Exception {
        JsonObject header = header("Observation");
        byte[] body = new byte[]{1, 2, 3, 4};

        byte[] encoded = Framing.encode(header, body);
        Framing.Frame decoded = Framing.read(new ByteArrayInputStream(encoded));

        assertEquals("Observation", decoded.type());
        assertEquals(1, decoded.header().get("n").getAsInt());
        assertArrayEquals(body, decoded.body());
    }

    @Test
    void handlesOneByteAtATime() throws Exception {
        JsonObject header = header("Ping");
        byte[] encoded = Framing.encode(header, null);

        InputStream slow = new InputStream() {
            private int index;

            @Override
            public int read() {
                return index < encoded.length ? encoded[index++] & 0xFF : -1;
            }
        };

        Framing.Frame decoded = Framing.read(slow);
        assertEquals("Ping", decoded.type());
        assertEquals(0, decoded.body().length);
    }

    @Test
    void writeFlushesCompleteFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Framing.write(out, header("Pong"), new byte[]{9});

        Framing.Frame decoded = Framing.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("Pong", decoded.type());
        assertArrayEquals(new byte[]{9}, decoded.body());
    }

    @Test
    void rejectsHeaderLengthPastFrameLength() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(8);
        buffer.putInt(8);

        IOException error = assertThrows(IOException.class,
                () -> Framing.read(new ByteArrayInputStream(buffer.array())));
        assertTrue(error.getMessage().contains("headerLen exceeds"));
    }

    @Test
    void rejectsOversizedHeaderLength() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(4 + Framing.MAX_HEADER_BYTES + 1);
        buffer.putInt(Framing.MAX_HEADER_BYTES + 1);

        IOException error = assertThrows(IOException.class,
                () -> Framing.read(new ByteArrayInputStream(buffer.array())));
        assertTrue(error.getMessage().contains("invalid headerLen"));
    }

    @Test
    void rejectsTruncatedFrame() throws Exception {
        byte[] encoded = Framing.encode(header("Ping"), new byte[]{1, 2, 3});
        byte[] truncated = new byte[encoded.length - 1];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);

        assertThrows(IOException.class,
                () -> Framing.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    void rejectsOversizedEncodedHeader() {
        JsonObject header = header("TooLarge");
        header.addProperty("payload", "x".repeat(Framing.MAX_HEADER_BYTES));

        assertThrows(IOException.class, () -> Framing.encode(header, null));
    }

    private static JsonObject header(String type) {
        JsonObject header = new JsonObject();
        header.addProperty("type", type);
        header.addProperty("n", 1);
        return header;
    }
}
