package com.vlamarionette.marionette;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VLA-Marionette client entry point.
 *
 * <p>This is the G1 scaffold: it only proves that the mod loads. Frame capture,
 * state reporting, transport, and action injection are added by later
 * milestones and must not be claimed before they are verified in game.
 */
@Mod(value = MarionetteMod.MOD_ID, dist = Dist.CLIENT)
public final class MarionetteMod {

    public static final String MOD_ID = "vla_marionette";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public MarionetteMod(IEventBus modBus) {
        LOG.info("[vla] VLA-Marionette loaded (client, MC 26.1.2 / NeoForge 26.1.2.126)");
    }
}
