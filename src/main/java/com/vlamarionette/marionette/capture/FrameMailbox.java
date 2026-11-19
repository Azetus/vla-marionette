package com.vlamarionette.marionette.capture;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-slot latest-frame mailbox feeding the JPEG encoder thread.
 *
 * <p>Sampling happens on the render thread; encoding is CPU-heavy and must not
 * block it. If a new frame arrives while the encoder is busy, the pending slot
 * is replaced, so a slow encoder loses old frames instead of accumulating
 * latency or retaining a stale newest frame.
 */
public final class FrameMailbox {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    @FunctionalInterface
    public interface FrameSink {
        boolean offer(JsonObject header, byte[] body);
    }

    public record PendingFrame(long frameId, long tick, String sessionId,
                               int width, int height, byte[] rgb, long captureNanos) {
    }

    private final FrameSink sink;
    private final float quality;

    private final Object lock = new Object();
    private PendingFrame pending;
    private boolean running;
    private Thread encoderThread;

    private final AtomicInteger sampled = new AtomicInteger();
    private final AtomicInteger replaced = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger encodeFailures = new AtomicInteger();

    public FrameMailbox(FrameSink sink, float quality) {
        this.sink = sink;
        this.quality = quality;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            encoderThread = new Thread(this::encodeLoop, "vla-jpeg-encoder");
            encoderThread.setDaemon(true);
            encoderThread.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            pending = null;
            lock.notifyAll();
        }
        Thread thread = encoderThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Replace the pending frame; returns false if the mailbox is stopped. */
    public boolean offer(PendingFrame frame) {
        synchronized (lock) {
            if (!running) {
                return false;
            }
            sampled.incrementAndGet();
            if (pending != null) {
                replaced.incrementAndGet();
            }
            pending = frame;
            lock.notifyAll();
            return true;
        }
    }

    public int sampled() {
        return sampled.get();
    }

    public int replaced() {
        return replaced.get();
    }

    public int sent() {
        return sent.get();
    }

    public int dropped() {
        return dropped.get();
    }

    public int encodeFailures() {
        return encodeFailures.get();
    }

    private void encodeLoop() {
        while (true) {
            PendingFrame frame;
            synchronized (lock) {
                while (running && pending == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (!running) {
                            return;
                        }
                    }
                }
                if (!running) {
                    return;
                }
                frame = pending;
                pending = null;
            }

            try {
                byte[] jpeg = JpegEncoder.encode(frame.rgb(), frame.width(), frame.height(),
                        quality);
                JsonObject header = new JsonObject();
                header.addProperty("type", "Observation");
                header.addProperty("session_id", frame.sessionId());
                header.addProperty("obs_id", frame.frameId());
                header.addProperty("state_tick", frame.tick());
                header.addProperty("submit_ns", frame.captureNanos());
                header.addProperty("img_w", frame.width());
                header.addProperty("img_h", frame.height());
                header.addProperty("pixel_format", "jpeg");

                if (sink.offer(header, jpeg)) {
                    sent.incrementAndGet();
                } else {
                    dropped.incrementAndGet();
                }
            } catch (Throwable t) {
                int failures = encodeFailures.incrementAndGet();
                if (failures <= 3 || failures % 50 == 0) {
                    LOG.warn("[vla] jpeg encode failed ({} times)", failures, t);
                }
                dropped.incrementAndGet();
            }
        }
    }
}
