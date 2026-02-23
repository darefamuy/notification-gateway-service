package com.abbank.notification;

import com.abbank.notification.channel.ChannelAdapter;
import com.abbank.notification.channel.ChannelAdapterFactory;
import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.consumer.*;
import com.abbank.notification.health.HealthServer;
import com.abbank.notification.retry.RetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AB Bank Notification Gateway — main entry point.
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *   <li>Load and validate configuration</li>
 *   <li>Build channel adapters (fail fast if none are configured)</li>
 *   <li>Start health check HTTP server</li>
 *   <li>Start the Kafka consumer loop on a dedicated thread</li>
 *   <li>Register JVM shutdown hook for graceful drain</li>
 * </ol>
 */
public class NotificationGatewayApp {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationGatewayApp.class);

    public static void main(final String[] args) throws Exception {
        LOG.info("=================================================");
        LOG.info("  AB Bank Notification Gateway  v1.0.0");
        LOG.info("=================================================");

        // ── 1. Configuration ──────────────────────────────────────────────────
        final GatewayConfig config = GatewayConfig.load();
        LOG.info("Configuration loaded. Bootstrap: {}, Topics: {}",
                config.getBootstrapServers(), config.getTopics());

        // ── 2. Channel adapters ───────────────────────────────────────────────
        final List<ChannelAdapter> emailAdapters = ChannelAdapterFactory.buildEmailAdapters(config);
        final List<ChannelAdapter> smsAdapters   = ChannelAdapterFactory.buildSmsAdapters(config);

        if (emailAdapters.isEmpty() && smsAdapters.isEmpty()) {
            LOG.error("No channel adapters are configured — refusing to start. " +
                      "Set at least one email or SMS provider's credentials.");
            System.exit(1);
        }

        // ── 3. Core services ──────────────────────────────────────────────────
        final RetryExecutor        retry      = new RetryExecutor(config);
        final CustomerResolver     resolver   = buildCustomerResolver(config);
        final NotificationDispatcher dispatcher = new NotificationDispatcher(
                emailAdapters, smsAdapters, retry, config);
        final NotificationConsumer consumer   = new NotificationConsumer(
                config, dispatcher, resolver);

        // ── 4. Health server ──────────────────────────────────────────────────
        final HealthServer health = new HealthServer(config.getHealthPort());
        health.start();

        // ── 5. Consumer thread ────────────────────────────────────────────────
        final ExecutorService executor   = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "notification-consumer");
            t.setDaemon(false);
            return t;
        });
        final CountDownLatch shutdownLatch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                health.markReady();
                consumer.run();
            } finally {
                health.markNotReady();
                shutdownLatch.countDown();
            }
        });

        // ── 6. Shutdown hook ──────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook triggered — starting graceful shutdown...");
            health.markNotReady();
            consumer.shutdown();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warn("Consumer thread did not terminate in 30s — forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            // Close all adapters
            emailAdapters.forEach(ChannelAdapter::close);
            smsAdapters.forEach(ChannelAdapter::close);
            health.stop();
            LOG.info("Notification Gateway shut down cleanly.");
        }, "shutdown-hook"));

        LOG.info("Notification Gateway is running. Press Ctrl+C to stop.");
        shutdownLatch.await();
    }

    private static CustomerResolver buildCustomerResolver(final GatewayConfig config) {
        final String type = config.getCustomerResolverType();
        return switch (type.toLowerCase()) {
            case "http" -> {
                LOG.info("Using HTTP customer resolver: {}", config.getCustomerServiceBaseUrl());
                yield new HttpCustomerResolver(
                        config.getCustomerServiceBaseUrl(),
                        config.getCustomerServiceTimeoutMs());
            }
            case "mock" -> {
                LOG.warn("Using MOCK customer resolver — not suitable for production");
                yield new MockCustomerResolver();
            }
            default -> throw new IllegalArgumentException("Unknown customer resolver type: " + type);
        };
    }
}
