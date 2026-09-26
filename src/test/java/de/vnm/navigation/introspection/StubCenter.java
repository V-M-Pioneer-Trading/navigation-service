package de.vnm.navigation.introspection;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A real HTTP stand-in for auth-service, on a loopback port, built on the JDK's own
 * {@code com.sun.net.httpserver} so the client under test talks to it over a real socket.
 *
 * <p>Every request is counted and recorded, on <i>any</i> path, so a client that appended
 * to the endpoint URL is both counted and seen; only the configured path gets the scripted
 * answer, anything else a 404.
 *
 * <p>{@link #hangingUp()} is the fixture's {@code transport: "no-response"}: a listener
 * that accepts a connection, counts it, and closes it without a byte. A retry would show as
 * a second accept.
 */
public final class StubCenter implements AutoCloseable {

    /** The path the fixture's {@code contract.endpoint.path} names. */
    public static final String PATH = "/auth/v1/introspect";

    /** What the stub answers one request with. */
    public record Reply(int status, String body, long delayMs, long stallMidBodyMs, Map<String, String> headers) {

        public static Reply of(int status, String body) {
            return new Reply(status, body, 0, 0, Map.of());
        }

        public static Reply delayed(long delayMs, int status, String body) {
            return new Reply(status, body, delayMs, 0, Map.of());
        }

        /**
         * Sends the status line, the headers and the first half of the body at once, then
         * stalls for {@code stallMs} before the rest: a center that answers instantly and
         * then dribbles.
         */
        public static Reply stallingMidBody(long stallMs, int status, String body) {
            return new Reply(status, body, 0, stallMs, Map.of());
        }

        public static Reply redirectTo(String location) {
            return new Reply(307, null, 0, 0, Map.of("Location", location));
        }

        public static Reply inactive() {
            return of(200, "{\"active\":false}");
        }
    }

    /** One request as the stub received it. */
    public record Received(String method, String path, String rawQuery, String requestUri,
                           Headers headers, String body) {

        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final AtomicInteger calls = new AtomicInteger();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final String url;
    private final AutoCloseable server;

    private StubCenter(String url, AutoCloseable server) {
        this.url = url;
        this.server = server;
    }

    /** A center that answers every request at {@link #PATH} with {@code script}. */
    public static StubCenter answering(Function<Received, Reply> script) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "stub-center");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(threads);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
            StubCenter center = new StubCenter(url, () -> {
                server.stop(0);
                threads.shutdownNow();
            });
            server.createContext("/", exchange -> center.handle(exchange, script));
            server.start();
            return center;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A center that answers every request with the same reply. */
    public static StubCenter answering(Reply reply) {
        return answering(request -> reply);
    }

    /** A center that accepts connections and hangs up on them without answering. */
    public static StubCenter hangingUp() {
        try {
            ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            String url = "http://127.0.0.1:" + listener.getLocalPort() + PATH;
            StubCenter center = new StubCenter(url, listener::close);
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket connection = listener.accept()) {
                        center.calls.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "stub-center-hang-up");
            acceptor.setDaemon(true);
            acceptor.start();
            return center;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The full endpoint URL, {@link #PATH} included, as {@code AUTH_INTROSPECTION_URL} carries it. */
    public String url() {
        return url;
    }

    public int calls() {
        return calls.get();
    }

    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    public void resetCalls() {
        calls.set(0);
        received.clear();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange, Function<Received, Reply> script) throws IOException {
        calls.incrementAndGet();
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders(), body);
        received.add(request);

        if (!PATH.equals(request.path())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Reply reply = script.apply(request);
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        reply.headers().forEach(exchange.getResponseHeaders()::set);
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (reply.stallMidBodyMs() > 0) {
                    out.write(bytes, 0, bytes.length / 2);
                    out.flush();
                    Thread.sleep(reply.stallMidBodyMs());
                    out.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2);
                } else {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // The client gave up (its timeout) before the delayed answer was written.
        } finally {
            exchange.close();
        }
    }
}
