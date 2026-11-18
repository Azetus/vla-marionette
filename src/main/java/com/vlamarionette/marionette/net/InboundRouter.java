package com.vlamarionette.marionette.net;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes inbound messages on the network read thread.
 *
 * <p>G2 supports only connectivity probing. Action, state, and observation
 * handlers are added by later milestones together with their capabilities.
 */
public final class InboundRouter {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private InboundRouter() {
    }

    public static void handle(Transport transport, JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        switch (type) {
            case "Ping" -> replyPong(transport, message);
            case "Pong" -> LOG.debug("[vla] pong nonce={}",
                    message.has("nonce") ? message.get("nonce").getAsString() : "");
            default -> LOG.warn("[vla] unsupported inbound message type: {}", type);
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
