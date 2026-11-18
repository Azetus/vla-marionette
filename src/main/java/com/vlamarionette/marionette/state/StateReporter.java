package com.vlamarionette.marionette.state;

import com.vlamarionette.marionette.capture.CaptureScheduler;
import com.vlamarionette.marionette.capture.CaptureStatus;
import com.vlamarionette.marionette.net.Transport;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Sends the periodic {@code State} report.
 *
 * <p>Driven by both client ticks and render frames with one wall-clock throttle:
 * client ticks provide a stable 10 Hz cadence even when the window renders
 * slowly, while render frames keep the report alive if ticks are stalled. The
 * state is sent even when no world/player exists.
 */
public final class StateReporter {

    private static final long INTERVAL_MS = 100L;

    private final Transport transport;
    private final CaptureScheduler captureScheduler;

    private long lastSentMs;

    public StateReporter(Transport transport, CaptureScheduler captureScheduler) {
        this.transport = transport;
        this.captureScheduler = captureScheduler;
    }

    public void onClientTick(ClientTickEvent.Post event) {
        report();
    }

    public void onRenderFrame(RenderFrameEvent.Post event) {
        report();
    }

    private void report() {
        long now = System.currentTimeMillis();
        if (now - lastSentMs < INTERVAL_MS) {
            return;
        }
        String sessionId = transport.sessionId();
        if (sessionId == null) {
            return;
        }
        lastSentMs = now;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        boolean inWorld = level != null;

        Long gameTime = level != null ? level.getGameTime() : null;
        String dimension = level != null ? level.dimension().identifier().toString() : null;
        Double x = null;
        Double y = null;
        Double z = null;
        Double yaw = null;
        Double pitch = null;
        Float health = null;
        Boolean alive = null;
        Boolean onGround = null;
        Integer hotbar = null;
        if (player != null) {
            x = round(player.getX());
            y = round(player.getY());
            z = round(player.getZ());
            yaw = round(player.getYRot());
            pitch = round(player.getXRot());
            health = player.getHealth();
            alive = player.isAlive();
            onGround = player.onGround();
            hotbar = player.getInventory().getSelectedSlot();
        }

        CaptureStatus capture = captureScheduler.status();
        capture = new CaptureStatus(capture.valid(), capture.reason(),
                capture.lastObsId(), capture.lastFrameTick(), captureScheduler.encodeFailures());

        StateSnapshot snapshot = new StateSnapshot(
                ClientClock.tick(),
                gameTime,
                inWorld,
                dimension,
                x, y, z, yaw, pitch,
                health, alive, onGround, hotbar,
                mc.screen != null ? mc.screen.getClass().getSimpleName() : null,
                mc.screen != null,
                false,
                List.of(),
                capture);

        transport.offerControl(snapshot.toJson(sessionId), null);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
