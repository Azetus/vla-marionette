package com.vlamarionette.marionette;

import com.vlamarionette.marionette.capture.CaptureScheduler;
import com.vlamarionette.marionette.capture.CaptureSpec;
import com.vlamarionette.marionette.capture.FrameMailbox;
import com.vlamarionette.marionette.mc.WindowLayout;
import com.vlamarionette.marionette.net.BridgeConfig;
import com.vlamarionette.marionette.net.InboundRouter;
import com.vlamarionette.marionette.net.Transport;
import com.vlamarionette.marionette.protocol.SessionInfo;
import com.vlamarionette.marionette.state.ClientClock;
import com.vlamarionette.marionette.state.StateReporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VLA-Marionette client entry point.
 *
 * <p>G3 adds window sizing and frame capture. State reporting and action
 * injection are added by later milestones and must not be claimed before they
 * are verified in game.
 */
@Mod(value = MarionetteMod.MOD_ID, dist = Dist.CLIENT)
public final class MarionetteMod {

    public static final String MOD_ID = "vla_marionette";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private final Transport transport;
    private final FrameMailbox frameMailbox;
    private final CaptureScheduler captureScheduler;
    private final StateReporter stateReporter;

    public MarionetteMod(IEventBus modBus) {
        BridgeConfig config = BridgeConfig.fromSystemProperties();
        transport = new Transport(config, SessionInfo::create);
        transport.setInboundHandler(message -> InboundRouter.handle(transport, message));
        transport.setOnConnect(session -> LOG.info("[vla] bridge session {}", session));
        transport.setOnDisconnect(() -> LOG.info("[vla] bridge disconnected"));
        transport.start();

        frameMailbox = new FrameMailbox(transport::offerFrame,
                CaptureSpec.ENCODE_WIDTH, CaptureSpec.ENCODE_HEIGHT, CaptureSpec.JPEG_QUALITY);
        frameMailbox.start();
        captureScheduler = new CaptureScheduler(transport, frameMailbox);
        stateReporter = new StateReporter(transport, captureScheduler);

        NeoForge.EVENT_BUS.addListener(ClientClock::onClientTick);
        NeoForge.EVENT_BUS.addListener(WindowLayout::onClientTick);
        NeoForge.EVENT_BUS.addListener(captureScheduler::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(stateReporter::onClientTick);
        NeoForge.EVENT_BUS.addListener(stateReporter::onRenderFrame);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            frameMailbox.stop();
            transport.stop();
        }, "vla-shutdown"));

        LOG.info("[vla] VLA-Marionette loaded (client, MC 26.1.2 / NeoForge 26.1.2.126)");
    }
}
