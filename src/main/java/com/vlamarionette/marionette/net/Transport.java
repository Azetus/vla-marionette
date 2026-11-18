package com.vlamarionette.marionette.net;

import com.google.gson.JsonObject;
import com.vlamarionette.marionette.protocol.Framing;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod-to-host TCP transport.
 *
 * <p>The mod dials the host. Each successful connection is a new session: a
 * fresh {@code session_id} is generated, a {@link SessionInfo} handshake is
 * queued before any other message, and all queued state from the previous
 * connection is discarded.
 *
 * <p>Outbound traffic has two classes:
 * <ul>
 *   <li><b>control</b> (handshake, receipts, state): bounded FIFO; if it cannot
 *       be drained, the connection is closed rather than silently dropping a
 *       contract message;</li>
 *   <li><b>frames</b>: one latest-wins slot; a slow consumer loses old frames,
 *       never the newest one.</li>
 * </ul>
 *
 * <p>Threads are daemons named {@code vla-connect/read/write}. The class has no
 * Minecraft dependencies and is exercised by unit tests with a fake host.
 */
public final class Transport {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int CONTROL_CAPACITY = 64;
    private static final int WRITE_POLL_MS = 10;
    private static final int IDLE_POLL_MS = 200;

    private final String host;
    private final int port;
    private final int retryMillis;
    private final Function<String, JsonObject> handshakeFactory;

    private final BlockingQueue<Outbound> control = new ArrayBlockingQueue<>(CONTROL_CAPACITY);
    private final AtomicReference<Outbound> latestFrame = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Conn conn;
    private volatile String sessionId;
    private volatile boolean accepting;

    private volatile Consumer<JsonObject> inboundHandler = message -> {
    };
    private volatile Consumer<String> onConnect = id -> {
    };
    private volatile Runnable onDisconnect = () -> {
    };

    private volatile Thread connectThread;
    private volatile Thread readThread;
    private volatile Thread writeThread;

    public Transport(BridgeConfig config, Function<String, JsonObject> handshakeFactory) {
        Objects.requireNonNull(config, "config");
        this.host = config.host();
        this.port = config.port();
        this.retryMillis = config.retryMillis();
        this.handshakeFactory = Objects.requireNonNull(handshakeFactory, "handshakeFactory");
    }

    public void setInboundHandler(Consumer<JsonObject> handler) {
        this.inboundHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setOnConnect(Consumer<String> handler) {
        this.onConnect = Objects.requireNonNull(handler, "handler");
    }

    public void setOnDisconnect(Runnable handler) {
        this.onDisconnect = Objects.requireNonNull(handler, "handler");
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LOG.info("[vla] bridge target {}:{}", host, port);
        connectThread = daemon("vla-connect", this::connectLoop);
        readThread = daemon("vla-read", this::readLoop);
        writeThread = daemon("vla-write", this::writeLoop);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Conn c = conn;
        if (c != null) {
            close(c);
        }
        control.clear();
        latestFrame.set(null);
        interrupt(connectThread);
        interrupt(readThread);
        interrupt(writeThread);
    }

    public boolean connected() {
        Conn c = conn;
        return c != null && !c.socket().isClosed();
    }

    /** Current or most recent session id; {@code null} before the first connect. */
    public String sessionId() {
        return sessionId;
    }

    /** Queue a control message. Returns false when not accepting or the queue is full. */
    public boolean offerControl(JsonObject header, byte[] body) {
        Objects.requireNonNull(header, "header");
        Conn c = conn;
        if (c == null || !accepting) {
            return false;
        }
        return enqueueControl(new Outbound(c.sessionId(), withSession(header, c.sessionId()), body));
    }

    /**
     * Queue a control message only while the current connection is still the
     * given session. Used for delayed responses (queries/chat) whose request
     * belongs to one connection and must not leak into a newer one.
     */
    public boolean offerControlFor(String expectedSessionId, JsonObject header, byte[] body) {
        Objects.requireNonNull(expectedSessionId, "expectedSessionId");
        Objects.requireNonNull(header, "header");
        Conn c = conn;
        if (c == null || !accepting || !expectedSessionId.equals(c.sessionId())) {
            return false;
        }
        return enqueueControl(new Outbound(c.sessionId(), withSession(header, c.sessionId()), body));
    }

    /**
     * Replace the latest unsent frame. Returns false when not accepting or when
     * the frame was sampled in an older session.
     */
    public boolean offerFrame(JsonObject header, byte[] body) {
        Objects.requireNonNull(header, "header");
        Conn c = conn;
        if (c == null || !accepting) {
            return false;
        }
        String frameSession = header.has("session_id")
                ? header.get("session_id").getAsString()
                : c.sessionId();
        if (!frameSession.equals(c.sessionId())) {
            return false;
        }
        latestFrame.set(new Outbound(frameSession, withSession(header, frameSession), body));
        return true;
    }

    private boolean enqueueControl(Outbound message) {
        if (control.offer(message)) {
            return true;
        }
        LOG.warn("[vla] outbound control queue full; closing session");
        Conn c = conn;
        if (c != null) {
            close(c);
        }
        return false;
    }

    private static JsonObject withSession(JsonObject header, String sessionId) {
        JsonObject copy = header.deepCopy();
        copy.addProperty("session_id", sessionId);
        return copy;
    }

    private void connectLoop() {
        while (running.get()) {
            if (conn != null) {
                sleepQuietly(IDLE_POLL_MS);
                continue;
            }
            Socket socket = null;
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

                Conn c = new Conn(
                        socket,
                        socket.getInputStream(),
                        new BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                        UUID.randomUUID().toString());

                accepting = false;
                control.clear();
                latestFrame.set(null);
                conn = c;
                sessionId = c.sessionId();

                JsonObject handshake = handshakeFactory.apply(c.sessionId());
                if (handshake == null) {
                    throw new IOException("handshake factory returned null");
                }
                if (!enqueueControl(new Outbound(c.sessionId(), withSession(handshake, c.sessionId()), null))) {
                    throw new IOException("cannot enqueue handshake");
                }
                accepting = true;

                LOG.info("[vla] bridge connected session={}", c.sessionId());
                try {
                    onConnect.accept(c.sessionId());
                } catch (Throwable t) {
                    LOG.error("[vla] onConnect callback failed", t);
                }
            } catch (Throwable t) {
                closeQuietly(socket);
                if (conn != null) {
                    close(conn);
                }
                LOG.debug("[vla] bridge connect failed {}:{}: {}", host, port, t.getMessage());
                sleepQuietly(retryMillis);
            }
        }
    }

    private void readLoop() {
        while (running.get()) {
            Conn c = conn;
            if (c == null) {
                sleepQuietly(100);
                continue;
            }
            try {
                Framing.Frame frame = Framing.read(c.in());
                String incomingSession = frame.header().has("session_id")
                        ? frame.header().get("session_id").getAsString()
                        : null;
                if (incomingSession != null && !incomingSession.equals(c.sessionId())) {
                    LOG.warn("[vla] dropping inbound message for stale session {}", incomingSession);
                    continue;
                }
                Consumer<JsonObject> handler = inboundHandler;
                try {
                    handler.accept(frame.header());
                } catch (Throwable t) {
                    LOG.error("[vla] inbound handler failed; read loop continues", t);
                }
            } catch (IOException e) {
                if (conn == c) {
                    LOG.debug("[vla] bridge read ended: {}", e.getMessage());
                }
                close(c);
            } catch (Throwable t) {
                LOG.error("[vla] inbound frame failed; closing connection", t);
                close(c);
            }
        }
    }

    private void writeLoop() {
        while (running.get()) {
            Outbound message;
            try {
                message = control.poll(WRITE_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message == null) {
                message = latestFrame.getAndSet(null);
            }
            if (message == null) {
                continue;
            }

            Conn c = conn;
            if (c == null || !message.sessionId().equals(c.sessionId())) {
                continue;
            }
            try {
                Framing.write(c.out(), message.header(), message.body());
            } catch (IOException e) {
                LOG.debug("[vla] bridge write failed: {}", e.getMessage());
                close(c);
            }
        }
    }

    /** Close only if this is still the current connection, so an old writer cannot kill a new one. */
    private void close(Conn c) {
        closeQuietly(c.socket());
        if (conn != c) {
            return;
        }
        conn = null;
        accepting = false;
        control.clear();
        latestFrame.set(null);
        LOG.info("[vla] bridge disconnected session={}", c.sessionId());
        try {
            onDisconnect.run();
        } catch (Throwable t) {
            LOG.error("[vla] onDisconnect callback failed", t);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One decoded connection. Replaced as a whole; never mutate fields in place. */
    private record Conn(Socket socket, InputStream in, OutputStream out, String sessionId) {
    }

    private record Outbound(String sessionId, JsonObject header, byte[] body) {
        Outbound {
            body = body == null ? new byte[0] : body;
        }
    }
}
