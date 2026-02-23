package com.abbank.notification.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight HTTP health check server.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /health} — returns 200 OK while the gateway is running,
 *       503 during shutdown.</li>
 *   <li>{@code GET /health/live} — liveness probe (always 200 if JVM is alive).</li>
 *   <li>{@code GET /health/ready} — readiness probe (200 when consumer is subscribed).</li>
 * </ul>
 */
public class HealthServer {

    private static final Logger LOG = LoggerFactory.getLogger(HealthServer.class);

    private final HttpServer   server;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public HealthServer(final int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "health-server");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/health",       this::handleHealth);
        server.createContext("/health/live",  this::handleLive);
        server.createContext("/health/ready", this::handleReady);
    }

    public void start() {
        server.start();
        LOG.info("Health server started on port {}", server.getAddress().getPort());
    }

    /** Mark the gateway as ready to receive traffic. */
    public void markReady() {
        ready.set(true);
        LOG.info("Gateway marked as ready");
    }

    /** Mark the gateway as not ready (e.g. during shutdown). */
    public void markNotReady() {
        ready.set(false);
    }

    public void stop() {
        markNotReady();
        server.stop(1);
        LOG.info("Health server stopped");
    }

    private void handleHealth(final HttpExchange exchange) throws IOException {
        respond(exchange, ready.get() ? 200 : 503,
                ready.get() ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
    }

    private void handleLive(final HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"ALIVE\"}");
    }

    private void handleReady(final HttpExchange exchange) throws IOException {
        respond(exchange, ready.get() ? 200 : 503,
                ready.get() ? "{\"status\":\"READY\"}" : "{\"status\":\"NOT_READY\"}");
    }

    private void respond(final HttpExchange exchange,
                         final int statusCode,
                         final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
