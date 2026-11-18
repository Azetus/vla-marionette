package com.vlamarionette.marionette.net;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.action.ActionChunk;
import com.vlamarionette.marionette.action.ActionParseException;
import com.vlamarionette.marionette.action.ActionReceipt;
import com.vlamarionette.marionette.action.ActionScheduler;
import com.vlamarionette.marionette.state.ClientClock;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes inbound messages on the network read thread.
 *
 * <p>Parsing and acceptance are network-thread concerns; all Minecraft input
 * state changes are scheduled with {@code Minecraft.execute}.
 */
public final class InboundRouter {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private InboundRouter() {
    }

    public static void handle(Transport transport, ActionScheduler scheduler, JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        switch (type) {
            case "Ping" -> replyPong(transport, message);
            case "Pong" -> LOG.debug("[vla] pong nonce={}",
                    message.has("nonce") ? message.get("nonce").getAsString() : "");
            case "ActionChunk" -> handleAction(transport, scheduler, message);
            case "Shutdown" -> ShutdownHandler.handle(transport, message);
            case "Release" -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    scheduler.resetSession();
                } else {
                    mc.execute(() -> scheduler.release(ClientClock.tick(), "release"));
                }
            }
            default -> LOG.warn("[vla] unsupported inbound message type: {}", type);
        }
    }

    private static void handleAction(Transport transport, ActionScheduler scheduler, JsonObject message) {
        ActionChunk chunk;
        try {
            chunk = ActionChunk.parse(message);
        } catch (ActionParseException e) {
            reject(transport, safeSeq(message), e.reason());
            LOG.warn("[vla] rejected ActionChunk: {} ({})", e.reason(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            reject(transport, safeSeq(message), "bad_shape");
            LOG.warn("[vla] rejected ActionChunk: bad_shape", e);
            return;
        }

        ActionScheduler.SubmitResult result = scheduler.submit(chunk, ClientClock.tick());
        if (!result.accepted()) {
            reject(transport, chunk.seq(), result.reason());
        }
    }

    private static void reject(Transport transport, int seq, String reason) {
        if (!transport.offerControl(ActionReceipt.rejected(seq, reason), null)) {
            LOG.warn("[vla] could not enqueue rejected receipt seq={} reason={}", seq, reason);
        }
    }

    private static int safeSeq(JsonObject message) {
        try {
            return message.has("seq") ? message.get("seq").getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void replyPong(Transport transport, JsonObject ping) {
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "Pong");
        if (ping.has("nonce")) {
            pong.addProperty("nonce", ping.get("nonce").getAsString());
        }
        if (!transport.offerControl(pong, null)) {
            LOG.warn("[vla] could not enqueue Pong");
        }
    }
}
