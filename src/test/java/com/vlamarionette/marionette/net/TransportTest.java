package com.vlamarionette.marionette.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.action.ActionFrame;
import com.vlamarionette.marionette.action.ActionScheduler;
import com.vlamarionette.marionette.action.InputSink;
import com.vlamarionette.marionette.protocol.Framing;
import com.vlamarionette.marionette.protocol.SessionInfo;
import com.vlamarionette.marionette.query.QueryService;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransportTest {

    @Test
    void handshakePingAndReconnectCreateNewSessions() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);

            Transport transport = new Transport(
                    new BridgeConfig("127.0.0.1", server.getLocalPort(), 50),
                    SessionInfo::create);
            CountDownLatch disconnected = new CountDownLatch(1);
            transport.setOnDisconnect(disconnected::countDown);
            ActionScheduler scheduler = new ActionScheduler(new InputSink() {
                @Override
                public void applyFrame(ActionFrame frame) {
                }

                @Override
                public void flushMouse() {
                }

                @Override
                public void releaseAll() {
                }
            }, receipt -> {
            });
            QueryService queries = new QueryService(transport);
            transport.setInboundHandler(message ->
                    InboundRouter.handle(transport, scheduler, queries, message));
            transport.start();

            try {
                String firstSession;
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    Framing.Frame handshake = Framing.read(socket.getInputStream());
                    assertEquals("SessionInfo", handshake.type());
                    firstSession = handshake.header().get("session_id").getAsString();
                    assertEquals(firstSession, transport.sessionId());
                    assertTrue(transport.connected());

                    JsonObject ping = new JsonObject();
                    ping.addProperty("type", "Ping");
                    ping.addProperty("nonce", "n-1");
                    Framing.write(socket.getOutputStream(), ping, null);

                    Framing.Frame pong = Framing.read(socket.getInputStream());
                    assertEquals("Pong", pong.type());
                    assertEquals("n-1", pong.header().get("nonce").getAsString());
                    assertEquals(firstSession, pong.header().get("session_id").getAsString());
                }

                assertTrue(disconnected.await(5, TimeUnit.SECONDS));

                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    Framing.Frame handshake = Framing.read(socket.getInputStream());
                    String secondSession = handshake.header().get("session_id").getAsString();
                    assertNotEquals(firstSession, secondSession);
                    assertEquals(secondSession, transport.sessionId());

                    JsonObject stale = new JsonObject();
                    stale.addProperty("type", "Observation");
                    stale.addProperty("session_id", firstSession);
                    assertFalse(transport.offerFrame(stale, new byte[]{1}));

                    JsonObject frame = new JsonObject();
                    frame.addProperty("type", "Observation");
                    frame.addProperty("session_id", secondSession);
                    assertTrue(transport.offerFrame(frame, new byte[]{1, 2, 3}));
                    Framing.Frame sent = Framing.read(socket.getInputStream());
                    assertEquals("Observation", sent.type());
                    assertEquals(secondSession, sent.header().get("session_id").getAsString());
                    assertArrayEquals(new byte[]{1, 2, 3}, sent.body());

                    JsonObject staleControl = new JsonObject();
                    staleControl.addProperty("type", "QueryBlockResult");
                    assertFalse(transport.offerControlFor(firstSession, staleControl, null));

                    JsonObject freshControl = new JsonObject();
                    freshControl.addProperty("type", "QueryInventoryResult");
                    assertTrue(transport.offerControlFor(secondSession, freshControl, null));
                    Framing.Frame control = Framing.read(socket.getInputStream());
                    assertEquals("QueryInventoryResult", control.type());
                    assertEquals(secondSession, control.header().get("session_id").getAsString());
                }
            } finally {
                transport.stop();
            }
        }
    }
}
