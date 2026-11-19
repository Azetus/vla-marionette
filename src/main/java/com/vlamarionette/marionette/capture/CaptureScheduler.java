package com.vlamarionette.marionette.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vlamarionette.marionette.net.Transport;
import com.vlamarionette.marionette.state.ClientClock;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the main render target at a wall-clock 5 Hz target and hands it to
 * the encoder.
 *
 * <p>All metadata (session id, observation id, state tick, sample time, image
 * size) is fixed at sampling time; encoding later cannot change it. Invalid
 * sampling conditions are exposed through {@link #status()} for the periodic
 * state report.
 */
public final class CaptureScheduler {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private final Transport transport;
    private final FrameMailbox mailbox;
    private final AtomicReference<CaptureStatus> status;

    private long nextFrameNanos = System.nanoTime();
    private long nextTick = Long.MIN_VALUE;
    private long nextObsId = 1;
    private long lastResizeMs;

    public CaptureScheduler(Transport transport, FrameMailbox mailbox) {
        this.transport = transport;
        this.mailbox = mailbox;
        this.status = new AtomicReference<>(
                CaptureStatus.invalid(CaptureStatus.NOT_READY, 0, 0, 0));
    }

    public CaptureStatus status() {
        return status.get();
    }

    public int encodeFailures() {
        return mailbox.encodeFailures();
    }

    public void onRenderFrame(RenderFrameEvent.Post event) {
        long now = System.nanoTime();
        if (CaptureSpec.mode() == CaptureMode.WALL_CLOCK) {
            if (now < nextFrameNanos) {
                return;
            }
            nextFrameNanos = now + CaptureSpec.frameIntervalNanos();
        } else {
            long tick = ClientClock.tick();
            if (nextTick == Long.MIN_VALUE) {
                nextTick = tick;
            }
            if (tick < nextTick) {
                return;
            }
            nextTick = tick + CaptureSpec.tickInterval();
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            if (CaptureSpec.mode() == CaptureMode.TICK_INTERVAL) {
                nextTick = Long.MIN_VALUE;
            }
            reject(CaptureStatus.NO_LEVEL);
            return;
        }

        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) {
            reject(CaptureStatus.NO_TARGET);
            return;
        }
        if (target.width != CaptureSpec.width() || target.height != CaptureSpec.height()) {
            requestResize(mc, target);
            reject(CaptureStatus.BAD_SIZE);
            return;
        }

        String sessionId = transport.sessionId();
        if (sessionId == null || !transport.connected()) {
            reject(CaptureStatus.NOT_READY);
            return;
        }

        long obsId = nextObsId++;
        long tick = ClientClock.tick();
        long sampleNanos = System.nanoTime();

        boolean accepted = FrameGrabber.grab(target, obsId, tick, sessionId, sampleNanos,
                (id, t, session, width, height, rgb, nanos) -> {
                    boolean queued = mailbox.offer(
                            new FrameMailbox.PendingFrame(id, t, session, width, height, rgb, nanos));
                    if (queued) {
                        status.set(CaptureStatus.valid(id, t, mailbox.encodeFailures()));
                    } else {
                        reject(CaptureStatus.NOT_READY);
                    }
                });
        if (!accepted) {
            reject(CaptureStatus.NO_TARGET);
        }
    }

    private void requestResize(Minecraft mc, RenderTarget target) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastResizeMs < 1000L) {
            return;
        }
        lastResizeMs = nowMs;
        LOG.info("[vla] request render size {}x{} (current {}x{})",
                CaptureSpec.width(), CaptureSpec.height(), target.width, target.height);
        mc.gameRenderer.resize(CaptureSpec.width(), CaptureSpec.height());
    }

    private void reject(String reason) {
        CaptureStatus previous = status.get();
        status.set(CaptureStatus.invalid(reason, previous.lastObsId(), previous.lastFrameTick(),
                mailbox.encodeFailures()));
    }
}
