package com.vlamarionette.marionette.state;

import com.vlamarionette.marionette.action.InputLock;
import com.vlamarionette.marionette.action.McInputInjector;
import com.vlamarionette.marionette.capture.CaptureScheduler;
import com.vlamarionette.marionette.capture.CaptureStatus;
import com.vlamarionette.marionette.net.Transport;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Sends the periodic {@code State} report.
 *
 * <p>Two pacing modes are supported:
 *
 * <ul>
 *   <li>{@code wall_clock}: driven by client ticks and render frames with one
 *       wall-clock throttle. Ticks provide a stable real-time cadence; render
 *       frames keep the report alive if ticks stall. This is the default and
 *       preserves the historical 10 Hz behavior.</li>
 *   <li>{@code tick}: called only from {@code ClientTickEvent.Post}, after the
 *       action scheduler and mouse flush, once every N client ticks. The
 *       {@code tick} field is the just-completed tick, so action ends are exact.
 *       There is no render fallback and no backfill.</li>
 * </ul>
 *
 * <p>The state is sent even when no world/player exists. Sampling on the tick
 * path is explicit from {@code MarionetteMod} so it is always ordered after the
 * action scheduler's mouse flush.
 */
public final class StateReporter {

    private final Transport transport;
    private final CaptureScheduler captureScheduler;
    private final McInputInjector inputInjector;
    private final StateConfig config;

    private long lastSentNanos;
    private long lastReportedTick = Long.MIN_VALUE;

    public StateReporter(Transport transport, CaptureScheduler captureScheduler,
            McInputInjector inputInjector, StateConfig config) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.captureScheduler = Objects.requireNonNull(captureScheduler, "captureScheduler");
        this.inputInjector = Objects.requireNonNull(inputInjector, "inputInjector");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Client-tick Post path. Must run after the action scheduler's Post step so
     * the final mouse delta is included in the pose.
     */
    public void onClientTickPost(long tick) {
        long now = System.nanoTime();
        if (config.tickMode()) {
            if (tick % config.tickInterval() != 0) {
                return;
            }
            report(tick, now);
            return;
        }
        reportWallClock(tick, now);
    }

    public void onRenderFrame(RenderFrameEvent.Post event) {
        if (config.tickMode()) {
            return;
        }
        reportWallClock(ClientClock.tick(), System.nanoTime());
    }

    private void reportWallClock(long tick, long now) {
        if (now - lastSentNanos < config.wallClockIntervalNanos()) {
            return;
        }
        report(tick, now);
    }

    private void report(long tick, long now) {
        String sessionId = transport.sessionId();
        if (sessionId == null) {
            return;
        }
        if (tick == lastReportedTick) {
            return;
        }
        lastReportedTick = tick;
        lastSentNanos = now;

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

        Set<Integer> heldKeys = new TreeSet<>(inputInjector.heldKeys());
        heldKeys.addAll(InputLock.physicalKeys());
        Set<Integer> heldButtons = new TreeSet<>(inputInjector.heldButtons());
        heldButtons.addAll(InputLock.physicalButtons());

        StateSnapshot snapshot = new StateSnapshot(
                tick,
                gameTime,
                inWorld,
                dimension,
                x, y, z, yaw, pitch,
                health, alive, onGround, hotbar,
                mc.screen != null ? mc.screen.getClass().getSimpleName() : null,
                mc.screen != null,
                InputLock.isLocked(),
                heldKeys,
                heldButtons,
                capture);

        transport.offerControl(snapshot.toJson(sessionId), null);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
