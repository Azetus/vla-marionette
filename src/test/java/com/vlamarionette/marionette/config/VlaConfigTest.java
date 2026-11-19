package com.vlamarionette.marionette.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vlamarionette.marionette.capture.CaptureMode;
import com.vlamarionette.marionette.mc.FovMode;
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
        assertEquals(FovMode.FIXED, config.fov().mode());
        assertEquals(70.0, config.fov().vertical());
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

                [fov]
                mode = "fixed"
                vertical = 90
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals("10.0.0.2", config.bridge().host());
        assertEquals(12345, config.bridge().port());
        assertEquals(320, config.capture().width());
        assertEquals(240, config.capture().height());
        assertEquals(CaptureMode.TICK_INTERVAL, config.capture().mode());
        assertEquals(10.0, config.capture().wallClockHz());
        assertEquals(2, config.capture().tickInterval());
        assertEquals(FovMode.FIXED, config.fov().mode());
        assertEquals(90.0, config.fov().vertical());
    }

    @Test
    void missingFovSectionUsesDefaultFixedSeventy(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [capture]
                width = 640
                height = 360
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals(640, config.capture().width());
        assertEquals(360, config.capture().height());
        assertEquals(FovMode.FIXED, config.fov().mode());
        assertEquals(70.0, config.fov().vertical());
    }

    @Test
    void invalidFovFallsBackToVanillaWithoutResettingCapture(@TempDir Path temp)
            throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [capture]
                width = 640
                height = 360
                mode = "tick"
                hz = 6.0
                tick_interval = 3

                [fov]
                mode = "fixed"
                vertical = 500
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals(640, config.capture().width());
        assertEquals(360, config.capture().height());
        assertEquals(CaptureMode.TICK_INTERVAL, config.capture().mode());
        assertEquals(6.0, config.capture().wallClockHz());
        assertEquals(3, config.capture().tickInterval());
        assertEquals(FovMode.VANILLA, config.fov().mode());
        assertEquals(70.0, config.fov().vertical());
    }

    @Test
    void unknownFovModeFallsBackToVanilla(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [fov]
                mode = "sometimes"
                vertical = 80
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals(FovMode.VANILLA, config.fov().mode());
    }

    @Test
    void vanillaModeIsHonored(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [fov]
                mode = "vanilla"
                vertical = 70
                """);

        VlaConfig config = VlaConfig.load(temp);

        assertEquals(FovMode.VANILLA, config.fov().mode());
        assertEquals(70.0, config.fov().vertical());
    }

    @Test
    void fovSystemOverrideWins(@TempDir Path temp) {
        try {
            System.setProperty("vla.fov.mode", "fixed");
            System.setProperty("vla.fov.vertical", "30");
            VlaConfig config = VlaConfig.load(temp);

            assertEquals(FovMode.FIXED, config.fov().mode());
            assertEquals(30.0, config.fov().vertical());
        } finally {
            System.clearProperty("vla.fov.mode");
            System.clearProperty("vla.fov.vertical");
        }
    }

    @Test
    void invalidFovSystemOverrideKeepsConfigValues(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve(VlaConfig.FILE_NAME), """
                [fov]
                mode = "fixed"
                vertical = 60
                """);
        try {
            System.setProperty("vla.fov.vertical", "500");
            VlaConfig config = VlaConfig.load(temp);

            assertEquals(FovMode.FIXED, config.fov().mode());
            assertEquals(60.0, config.fov().vertical());
        } finally {
            System.clearProperty("vla.fov.mode");
            System.clearProperty("vla.fov.vertical");
        }
    }
}
