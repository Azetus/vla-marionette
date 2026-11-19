package com.vlamarionette.marionette;

import com.vlamarionette.marionette.action.ActionScheduler;
import com.vlamarionette.marionette.action.InputLock;
import com.vlamarionette.marionette.action.McInputInjector;
import com.vlamarionette.marionette.capture.CaptureScheduler;
import com.vlamarionette.marionette.capture.CaptureSpec;
import com.vlamarionette.marionette.capture.FrameMailbox;
import com.vlamarionette.marionette.config.VlaConfig;
import com.vlamarionette.marionette.mc.WindowLayout;
import com.vlamarionette.marionette.net.BridgeConfig;
import com.vlamarionette.marionette.net.InboundRouter;
import com.vlamarionette.marionette.net.Transport;
import com.vlamarionette.marionette.protocol.SessionInfo;
import com.vlamarionette.marionette.query.QueryService;
import com.vlamarionette.marionette.state.ClientClock;
import com.vlamarionette.marionette.state.StateReporter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
    private final McInputInjector inputInjector;
    private final ActionScheduler actionScheduler;
    private boolean focusPauseDisabled;

    public MarionetteMod(IEventBus modBus) {
        VlaConfig config = VlaConfig.load(FMLPaths.CONFIGDIR.get());
        CaptureSpec.configure(config.capture());
        BridgeConfig bridge = config.bridge();
        transport = new Transport(bridge, SessionInfo::create);

        inputInjector = new McInputInjector();
        actionScheduler = new ActionScheduler(inputInjector, receipt -> {
            if (!transport.offerControl(receipt, null)) {
                LOG.debug("[vla] could not enqueue action receipt (session not accepting)");
            }
        });
        InputLock.setTakeoverHandler(
                () -> actionScheduler.release(ClientClock.tick(), "manual_takeover"));
        QueryService queryService = new QueryService(transport);
        transport.setInboundHandler(message ->
                InboundRouter.handle(transport, actionScheduler, queryService, message));
        transport.setOnConnect(session -> {
            LOG.info("[vla] bridge session {}", session);
            InputLock.arm();
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                // The first connection can win the race against Minecraft's construction.
                actionScheduler.resetSession();
            } else {
                mc.execute(actionScheduler::newSession);
            }
        });
        transport.setOnDisconnect(() -> {
            LOG.info("[vla] bridge disconnected");
            InputLock.disarm();
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                actionScheduler.resetSession();
            } else {
                mc.execute(() -> actionScheduler.release(ClientClock.tick(), "disconnected"));
            }
        });
        transport.start();

        frameMailbox = new FrameMailbox(transport::offerFrame, CaptureSpec.JPEG_QUALITY);
        frameMailbox.start();
        captureScheduler = new CaptureScheduler(transport, frameMailbox);
        stateReporter = new StateReporter(transport, captureScheduler, inputInjector);

        NeoForge.EVENT_BUS.addListener(ClientClock::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
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

    private void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!focusPauseDisabled) {
            mc.options.pauseOnLostFocus = false;
            focusPauseDisabled = true;
            LOG.info("[vla] disabled focus-loss auto-pause");
        }

        InputLock.onClientTick(mc.screen != null);
        if (InputLock.consumeReleasePending()) {
            // Arm/re-arm must not inherit physical controls pressed before the
            // session (or during ESC takeover): releases are blocked too.
            KeyMapping.releaseAll();
        }
        long tick = ClientClock.tick();
        if (mc.level == null) {
            actionScheduler.release(tick, "no_level");
            return;
        }
        actionScheduler.onPre(tick);
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        actionScheduler.onPost(ClientClock.tick());
    }
}
