package com.vlamarionette.marionette.capture;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Reads pixels from the offscreen main render target.
 *
 * <p>Reading the main target (instead of the window back buffer) keeps the
 * capture path identical when the window is unfocused, occluded, or minimized:
 * rendering still writes the main target, while only the final blit is skipped.
 *
 * <p>The readback is asynchronous; the sink runs on the render thread after the
 * GPU fence. Implementations must only copy data there and hand heavy work to
 * another thread.
 */
public final class FrameGrabber {

    public interface Sink {
        void onFrame(long frameId, long tick, String sessionId,
                     int width, int height, byte[] rgb, long captureNanos);
    }

    private FrameGrabber() {
    }

    public static boolean grab(RenderTarget target, long frameId, long tick,
                               String sessionId, long captureNanos, Sink sink) {
        if (target == null) {
            return false;
        }
        GpuTexture texture = target.getColorTexture();
        if (texture == null) {
            return false;
        }
        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0) {
            return false;
        }
        int pixelSize = texture.getFormat().pixelSize();

        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "vla-frame-" + frameId,
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) width * height * pixelSize);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
                byte[] rgb = new byte[width * height * 3];
                int out = 0;
                for (int y = 0; y < height; y++) {
                    int row = (height - 1 - y) * width; // GL origin is bottom-left
                    for (int x = 0; x < width; x++) {
                        // Little-endian packed RGBA => 0xAABBGGRR; low byte is R.
                        int packed = view.data().getInt((row + x) * pixelSize);
                        rgb[out++] = (byte) (packed & 0xFF);
                        rgb[out++] = (byte) ((packed >> 8) & 0xFF);
                        rgb[out++] = (byte) ((packed >> 16) & 0xFF);
                    }
                }
                sink.onFrame(frameId, tick, sessionId, width, height, rgb, captureNanos);
            } finally {
                buffer.close();
            }
        }, 0);
        return true;
    }
}
