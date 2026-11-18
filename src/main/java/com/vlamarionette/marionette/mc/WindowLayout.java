package com.vlamarionette.marionette.mc;

import com.vlamarionette.marionette.capture.CaptureSpec;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locks the window to the render target size and disables manual resizing.
 *
 * <p>Applied on the first client tick, after Minecraft and GLFW are fully
 * constructed. The render target itself is forced by
 * {@code GameRendererResizeMixin}; this class only sizes the window so the
 * blit is 1:1.
 */
public final class WindowLayout {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private static boolean applied;

    private WindowLayout() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (applied) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }

        mc.getWindow().setWindowed(CaptureSpec.RENDER_WIDTH, CaptureSpec.RENDER_HEIGHT);
        GLFW.glfwSetWindowAttrib(mc.getWindow().handle(), GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        applied = true;

        LOG.info("[vla] window locked to {}x{}", CaptureSpec.RENDER_WIDTH, CaptureSpec.RENDER_HEIGHT);
    }
}
