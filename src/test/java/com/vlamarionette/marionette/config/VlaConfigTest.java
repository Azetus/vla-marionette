package com.vlamarionette.marionette.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vlamarionette.marionette.capture.CaptureMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VlaConfigTest {

    @Test
    void createsAndReadsTheDefaultFile(@TempDir Path temp) {
        VlaConfig config = VlaConfig.load(temp);

        assertTrue(Files.exists(temp.resolve(VlaConfig.FILE_NAME)));
        assertEquals("127.0.0.1", config.bridge().host());
        assertEquals(25580, config.bridge().port());
        assertEquals(854, config.capture().width());
        assertEquals(480, config.capture().height());
        assertEquals(CaptureMode.WALL_CLOCK, config.capture().mode());
        assertEquals(5.0, config.capture().wallClockHz());
        assertEquals(4, config.capture().tickInterval());
    }

    @Test
    void readsCustomValuesAndTickMode(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [bridge]
                host = "10.0.0.2"
                port = 12345
                retry_ms = 500

                [capture]
                width = 320
                height = 240
                mode = "tick"
                hz = 10.0
                tick_interval = 2
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals("10.0.0.2", config.bridge().host());
        assertEquals(12345, config.bridge().port());
        assertEquals(320, config.capture().width());
        assertEquals(240, config.capture().height());
        assertEquals(CaptureMode.TICK_INTERVAL, config.capture().mode());
        assertEquals(10.0, config.capture().wallClockHz());
        assertEquals(2, config.capture().tickInterval());
    }
}
