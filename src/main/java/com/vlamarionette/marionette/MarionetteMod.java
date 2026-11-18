package com.vlamarionette.marionette;

import com.vlamarionette.marionette.net.BridgeConfig;
import com.vlamarionette.marionette.net.InboundRouter;
import com.vlamarionette.marionette.net.Transport;
import com.vlamarionette.marionette.protocol.SessionInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VLA-Marionette client entry point.
 *
 * <p>G2 adds the transport/session layer. Frame capture, state reporting, and
 * action injection are added by later milestones and must not be claimed before
 * they are verified in game.
 */
@Mod(value = MarionetteMod.MOD_ID, dist = Dist.CLIENT)
public final class MarionetteMod {

    public static final String MOD_ID = "vla_marionette";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private final Transport transport;

    public MarionetteMod(IEventBus modBus) {
        BridgeConfig config = BridgeConfig.fromSystemProperties();
        transport = new Transport(config, SessionInfo::create);
        transport.setInboundHandler(message -> InboundRouter.handle(transport, message));
        transport.setOnConnect(session -> LOG.info("[vla] bridge session {}", session));
        transport.setOnDisconnect(() -> LOG.info("[vla] bridge disconnected"));
        transport.start();

        Runtime.getRuntime().addShutdownHook(new Thread(transport::stop, "vla-shutdown"));

        LOG.info("[vla] VLA-Marionette loaded (client, MC 26.1.2 / NeoForge 26.1.2.126)");
    }
}
