package com.vlamarionette.marionette.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.protocol.Framing;
import com.vlamarionette.marionette.protocol.SessionInfo;
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
            transport.setInboundHandler(message -> InboundRouter.handle(transport, message));
            transport.start();

            try {
                String firstSession;
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    Framing.Frame handshake = Framing.read(socket.getInputStream());
                    assertEquals("SessionInfo", handshake.type());
                    assertEquals(1, handshake.header().get("protocol_version").getAsInt());
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
                }
            } finally {
                transport.stop();
            }
        }
    }
}
