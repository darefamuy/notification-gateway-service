package com.abbank.notification.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Typed configuration for the Notification Gateway, loaded from
 * {@code application.conf} via Typesafe Config.
 *
 * <p>All secrets (API keys, auth tokens) are read from environment variables
 * via Typesafe Config substitution (e.g. {@code ${?SENDGRID_API_KEY}}).
 * This class never holds or logs secret values — only their presence is checked.
 */
public final class GatewayConfig {

    private final Config raw;

    private GatewayConfig(final Config config) {
        this.raw = config;
    }

    public static GatewayConfig load() {
        return new GatewayConfig(ConfigFactory.load().resolve());
    }

    // ── Kafka ─────────────────────────────────────────────────────────────────

    public String getBootstrapServers() {
        return raw.getString("kafka.bootstrap-servers");
    }

    public String getConsumerGroupId() {
        return raw.getString("kafka.consumer.group-id");
    }

    public String getAutoOffsetReset() {
        return raw.getString("kafka.consumer.auto-offset-reset");
    }

    public int getMaxPollRecords() {
        return raw.getInt("kafka.consumer.max-poll-records");
    }

    public int getSessionTimeoutMs() {
        return raw.getInt("kafka.consumer.session-timeout-ms");
    }

    public int getHeartbeatIntervalMs() {
        return raw.getInt("kafka.consumer.heartbeat-interval-ms");
    }

    public List<String> getTopics() {
        return raw.getStringList("kafka.topics");
    }

    // ── Email providers ───────────────────────────────────────────────────────

    public List<? extends Config> getEmailProviders() {
        return raw.getConfigList("channels.email.providers");
    }

    /** Returns the first email provider config where enabled=true, if any. */
    public Optional<Config> getActiveEmailProvider() {
        return getEmailProviders().stream()
                .filter(c -> c.getBoolean("enabled"))
                .map(Config.class::cast)
                .findFirst();
    }

    /** Returns all email providers where enabled=true (for fallback chains). */
    public List<? extends Config> getActiveEmailProviders() {
        return getEmailProviders().stream()
                .filter(c -> c.getBoolean("enabled"))
                .collect(Collectors.toList());
    }

    // ── SMS providers ─────────────────────────────────────────────────────────

    public List<? extends Config> getSmsProviders() {
        return raw.getConfigList("channels.sms.providers");
    }

    public Optional<Config> getActiveSmsProvider() {
        return getSmsProviders().stream()
                .filter(c -> c.getBoolean("enabled"))
                .map(Config.class::cast)
                .findFirst();
    }

    public List<? extends Config> getActiveSmsProviders() {
        return getSmsProviders().stream()
                .filter(c -> c.getBoolean("enabled"))
                .collect(Collectors.toList());
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    public List<String> getForceBothOnSeverity() {
        return raw.getStringList("routing.force-both-on-severity");
    }

    // ── Customer resolver ─────────────────────────────────────────────────────

    public String getCustomerResolverType() {
        return raw.getString("customer-resolver.type");
    }

    public String getCustomerServiceBaseUrl() {
        return raw.getString("customer-resolver.http.base-url");
    }

    public int getCustomerServiceTimeoutMs() {
        return raw.getInt("customer-resolver.http.timeout-ms");
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    public int getRetryMaxAttempts() {
        return raw.getInt("retry.max-attempts");
    }

    public long getRetryInitialDelayMs() {
        return raw.getLong("retry.initial-delay-ms");
    }

    public double getRetryBackoffFactor() {
        return raw.getDouble("retry.backoff-factor");
    }

    public long getRetryMaxDelayMs() {
        return raw.getLong("retry.max-delay-ms");
    }

    public String getRetryOnExhausted() {
        return raw.getString("retry.on-exhausted");
    }

    public String getDlqTopic() {
        return raw.getString("retry.dlq-topic");
    }

    // ── Health ────────────────────────────────────────────────────────────────

    public int getHealthPort() {
        return raw.getInt("health.port");
    }
}
