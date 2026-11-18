package com.vlamarionette.marionette.net;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graceful host-initiated shutdown: acknowledge, save the integrated server,
 * then ask the client loop to close its window so Minecraft's normal destroy
 * path runs.
 */
public final class ShutdownHandler {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private ShutdownHandler() {
    }

    public static void handle(Transport transport, JsonObject message) {
        boolean save = !message.has("save") || message.get("save").getAsBoolean();
        String reason = message.has("reason") ? message.get("reason").getAsString() : "remote";

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ShutdownAck");
        ack.addProperty("save", save);
        ack.addProperty("reason", reason);
        if (!transport.offerControl(ack, null)) {
            LOG.warn("[vla] could not enqueue ShutdownAck");
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            LOG.info("[vla] shutdown requested reason={} save={}", reason, save);
            try {
                IntegratedServer server = mc.getSingleplayerServer();
                if (save && server != null) {
                    server.halt(true);
                    LOG.info("[vla] integrated server halted");
                }
            } catch (Throwable t) {
                LOG.error("[vla] shutdown save failed", t);
            }
            GLFW.glfwSetWindowShouldClose(mc.getWindow().handle(), true);
            LOG.info("[vla] window close requested");
        });
    }
}
