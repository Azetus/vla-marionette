package com.vlamarionette.marionette.query;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.net.Transport;
import com.vlamarionette.marionette.state.ClientClock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network-thread entry point for {@code QueryBlock}, {@code QueryInventory} and
 * {@code Chat}.
 *
 * <p>Shape/text validation happens here. Minecraft state is only read on the
 * client thread through {@link Minecraft#execute(Runnable)}. Responses are
 * control messages and carry the session of the request, so a delayed response
 * cannot leak into a newer connection.
 */
public final class QueryService {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    /** Maximum outstanding main-thread requests before answering {@code busy}. */
    public static final int MAX_PENDING = 32;

    private final Transport transport;
    private final AtomicInteger pending = new AtomicInteger();

    public QueryService(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void handleBlock(JsonObject message) {
        String session = transport.sessionId();
        QueryRequests.BlockQuery request;
        try {
            request = QueryRequests.parseBlock(message);
        } catch (QueryRequestException e) {
            respond(session, QueryResponses.blockError(
                    e.requestId(), ClientClock.tick(), e.reason()));
            return;
        }

        if (!tryAcquire()) {
            respond(session, QueryResponses.blockError(
                    request.requestId(), ClientClock.tick(), "busy"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            release();
            respond(session, QueryResponses.blockError(
                    request.requestId(), ClientClock.tick(), "not_ready"));
            return;
        }
        mc.execute(() -> {
            try {
                readBlock(mc, session, request);
            } finally {
                release();
            }
        });
    }

    public void handleInventory(JsonObject message) {
        String session = transport.sessionId();
        QueryRequests.InventoryQuery request;
        try {
            request = QueryRequests.parseInventory(message);
        } catch (QueryRequestException e) {
            respond(session, QueryResponses.inventoryError(
                    e.requestId(), ClientClock.tick(), e.reason()));
            return;
        }

        if (!tryAcquire()) {
            respond(session, QueryResponses.inventoryError(
                    request.requestId(), ClientClock.tick(), "busy"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            release();
            respond(session, QueryResponses.inventoryError(
                    request.requestId(), ClientClock.tick(), "not_ready"));
            return;
        }
        mc.execute(() -> {
            try {
                McQueryReader.InventorySnapshot snapshot = McQueryReader.readInventory(mc);
                respond(session, QueryResponses.inventoryOk(request.requestId(),
                        ClientClock.tick(), snapshot.selected(), snapshot.slots()));
            } catch (QueryFailure failure) {
                respond(session, QueryResponses.inventoryError(request.requestId(),
                        ClientClock.tick(), failure.reason()));
            } catch (Throwable t) {
                LOG.error("[vla] QueryInventory failed", t);
                respond(session, QueryResponses.inventoryError(request.requestId(),
                        ClientClock.tick(), "internal"));
            } finally {
                release();
            }
        });
    }

    public void handleSettings(JsonObject message) {
        String session = transport.sessionId();
        QueryRequests.SettingsQuery request;
        try {
            request = QueryRequests.parseSettings(message);
        } catch (QueryRequestException e) {
            respond(session, QueryResponses.settingsError(
                    e.requestId(), ClientClock.tick(), e.reason()));
            return;
        }

        if (!tryAcquire()) {
            respond(session, QueryResponses.settingsError(
                    request.requestId(), ClientClock.tick(), "busy"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            release();
            respond(session, QueryResponses.settingsError(
                    request.requestId(), ClientClock.tick(), "not_ready"));
            return;
        }
        mc.execute(() -> {
            try {
                McQueryReader.SettingsSnapshot settings = McQueryReader.readSettings(mc);
                respond(session, QueryResponses.settingsOk(request.requestId(),
                        ClientClock.tick(), settings.gameMode(), settings.sensitivity(),
                        settings.invertMouseX(), settings.invertMouseY(),
                        settings.smoothCamera(), settings.cameraType(),
                        settings.scoping(),
                        new QueryResponses.Fov(settings.fov(), settings.fovEffectScale(),
                                settings.fovMode()),
                        settings.hideGui(), settings.guiScale(),
                        settings.guiWidth(), settings.guiHeight(), settings.keybinds()));
            } catch (QueryFailure failure) {
                respond(session, QueryResponses.settingsError(request.requestId(),
                        ClientClock.tick(), failure.reason()));
            } catch (Throwable t) {
                LOG.error("[vla] QuerySettings failed", t);
                respond(session, QueryResponses.settingsError(request.requestId(),
                        ClientClock.tick(), "internal"));
            } finally {
                release();
            }
        });
    }

    public void handleChat(JsonObject message) {        String session = transport.sessionId();
        ChatRequest request;
        try {
            request = ChatRequest.parse(message);
        } catch (QueryRequestException e) {
            respond(session, QueryResponses.chatAck(
                    e.requestId(), false, e.reason(), null, ClientClock.tick()));
            return;
        }

        if (!tryAcquire()) {
            respond(session, QueryResponses.chatAck(request.requestId(), false, "busy",
                    request.kind(), ClientClock.tick()));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            release();
            respond(session, QueryResponses.chatAck(request.requestId(), false, "not_ready",
                    request.kind(), ClientClock.tick()));
            return;
        }
        mc.execute(() -> {
            try {
                String failure = McQueryReader.sendChat(mc, request);
                respond(session, QueryResponses.chatAck(request.requestId(), failure == null,
                        failure, request.kind(), ClientClock.tick()));
            } catch (Throwable t) {
                LOG.error("[vla] Chat dispatch failed", t);
                respond(session, QueryResponses.chatAck(request.requestId(), false, "internal",
                        request.kind(), ClientClock.tick()));
            } finally {
                release();
            }
        });
    }

    private void readBlock(Minecraft mc, String session, QueryRequests.BlockQuery request) {
        try {
            if (request.crosshair()) {
                McQueryReader.TargetSnapshot target = McQueryReader.readCrosshair(mc);
                McQueryReader.BlockSnapshot block = target.block();
                respond(session, QueryResponses.blockOk(request.requestId(),
                        ClientClock.tick(), true, block.x(), block.y(), block.z(),
                        block.blockState(), block.properties(), block.blockEntity(),
                        target.distance(), target.face()));
            } else {
                QueryRequests.Pos pos = request.pos();
                McQueryReader.BlockSnapshot block = McQueryReader.readBlock(
                        mc, pos.x(), pos.y(), pos.z());
                respond(session, QueryResponses.blockOk(request.requestId(),
                        ClientClock.tick(), false, block.x(), block.y(), block.z(),
                        block.blockState(), block.properties(), block.blockEntity(),
                        null, null));
            }
        } catch (QueryFailure failure) {
            respond(session, QueryResponses.blockError(request.requestId(),
                    ClientClock.tick(), failure.reason()));
        } catch (Throwable t) {
            LOG.error("[vla] QueryBlock failed", t);
            respond(session, QueryResponses.blockError(request.requestId(),
                    ClientClock.tick(), "internal"));
        }
    }

    private boolean tryAcquire() {
        int current = pending.incrementAndGet();
        if (current <= MAX_PENDING) {
            return true;
        }
        pending.decrementAndGet();
        return false;
    }

    private void release() {
        pending.decrementAndGet();
    }

    private void respond(String expectedSession, JsonObject message) {
        if (expectedSession == null) {
            return;
        }
        if (!transport.offerControlFor(expectedSession, message, null)) {
            LOG.debug("[vla] dropped query/chat response for a stale or closed session");
        }
    }
}
