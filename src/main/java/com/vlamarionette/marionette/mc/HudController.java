package com.vlamarionette.marionette.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-controlled HUD visibility.
 *
 * <p>{@code SetHud} directly sets {@code options.hideGui}; it does not record or
 * restore a baseline and does not write {@code options.txt}. A later vanilla F1
 * toggle wins until the host sends another {@code SetHud}.
 */
public final class HudController {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private HudController() {
    }

    public static void handle(JsonObject message) {
        JsonElement visibleElement = message.get("visible");
        if (visibleElement == null || !visibleElement.isJsonPrimitive()
                || !visibleElement.getAsJsonPrimitive().isBoolean()) {
            LOG.warn("[vla] ignored SetHud without boolean 'visible'");
            return;
        }
        boolean visible = visibleElement.getAsBoolean();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            LOG.warn("[vla] ignored SetHud before Minecraft was constructed");
            return;
        }
        mc.execute(() -> {
            mc.options.hideGui = !visible;
            LOG.info("[vla] HUD visible={} (hideGui={})", visible, mc.options.hideGui);
        });
    }
}
