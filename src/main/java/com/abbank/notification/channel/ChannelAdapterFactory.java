package com.abbank.notification.channel;

import com.abbank.notification.config.GatewayConfig;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and validates all {@link ChannelAdapter} instances from the application config.
 *
 * <p>Multiple providers of the same type are returned in priority order
 * (first-enabled wins). The {@link NotificationDispatcher} uses this list
 * to implement automatic fallback: if the first adapter fails, the next one
 * is tried until one succeeds or all are exhausted.
 */
public final class ChannelAdapterFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelAdapterFactory.class);

    private ChannelAdapterFactory() {}

    /**
     * Build all enabled email adapters in config priority order.
     */
    public static List<ChannelAdapter> buildEmailAdapters(final GatewayConfig config) {
        final List<ChannelAdapter> adapters = new ArrayList<>();

        for (final Config provider : config.getActiveEmailProviders()) {
            final String name = provider.getString("name");
            try {
                final ChannelAdapter adapter = buildEmailAdapter(name, provider);
                if (adapter.isConfigured()) {
                    adapters.add(adapter);
                    LOG.info("Email adapter ready: provider={}", name);
                } else {
                    LOG.warn("Email adapter '{}' is enabled in config but missing credentials — skipping", name);
                    adapter.close();
                }
            } catch (Exception e) {
                LOG.error("Failed to build email adapter '{}': {}", name, e.getMessage());
            }
        }

        if (adapters.isEmpty()) {
            LOG.warn("No email adapters are configured and operational. Email notifications will be skipped.");
        }
        return adapters;
    }

    /**
     * Build all enabled SMS adapters in config priority order.
     */
    public static List<ChannelAdapter> buildSmsAdapters(final GatewayConfig config) {
        final List<ChannelAdapter> adapters = new ArrayList<>();

        for (final Config provider : config.getActiveSmsProviders()) {
            final String name = provider.getString("name");
            try {
                final ChannelAdapter adapter = buildSmsAdapter(name, provider);
                if (adapter.isConfigured()) {
                    adapters.add(adapter);
                    LOG.info("SMS adapter ready: provider={}", name);
                } else {
                    LOG.warn("SMS adapter '{}' is enabled in config but missing credentials — skipping", name);
                    adapter.close();
                }
            } catch (Exception e) {
                LOG.error("Failed to build SMS adapter '{}': {}", name, e.getMessage());
            }
        }

        if (adapters.isEmpty()) {
            LOG.warn("No SMS adapters are configured and operational. SMS notifications will be skipped.");
        }
        return adapters;
    }

    // ── Private builders ──────────────────────────────────────────────────────

    private static ChannelAdapter buildEmailAdapter(final String name, final Config cfg) {
        return switch (name.toLowerCase()) {
            case "sendgrid" -> new SendGridEmailAdapter(
                    cfgStr(cfg, "api-key"),
                    cfgStr(cfg, "from"),
                    cfgStrOpt(cfg, "reply-to"));

            case "ses" -> new SesEmailAdapter(
                    System.getenv("AWS_ACCESS_KEY_ID"),
                    System.getenv("AWS_SECRET_ACCESS_KEY"),
                    cfgStr(cfg, "region"),
                    cfgStr(cfg, "from"));

            case "mailersend" -> new MailerSendEmailAdapter(
                    cfgStr(cfg, "api-key"),
                    cfgStr(cfg, "from"));

            case "postmark" -> new PostmarkEmailAdapter(
                    cfgStr(cfg, "server-token"),
                    cfgStr(cfg, "from"),
                    cfgStrOpt(cfg, "message-stream"));

            default -> throw new IllegalArgumentException("Unknown email provider: " + name);
        };
    }

    private static ChannelAdapter buildSmsAdapter(final String name, final Config cfg) {
        return switch (name.toLowerCase()) {
            case "africas-talking" -> new AfricasTalkingSmsAdapter(
                    cfgStr(cfg, "api-key"),
                    cfgStr(cfg, "username"),
                    cfgStr(cfg, "sender-id"),
                    cfg.hasPath("sandbox") && cfg.getBoolean("sandbox"));

            case "twilio" -> new TwilioSmsAdapter(
                    cfgStr(cfg, "account-sid"),
                    cfgStr(cfg, "auth-token"),
                    cfgStr(cfg, "from-number"));

            case "termii" -> new TermiiSmsAdapter(
                    cfgStr(cfg, "api-key"),
                    cfgStr(cfg, "sender-id"),
                    cfgStrOpt(cfg, "channel"));

            default -> throw new IllegalArgumentException("Unknown SMS provider: " + name);
        };
    }

    private static String cfgStr(final Config cfg, final String key) {
        return cfg.hasPath(key) ? cfg.getString(key) : "";
    }

    private static String cfgStrOpt(final Config cfg, final String key) {
        return cfg.hasPath(key) ? cfg.getString(key) : null;
    }
}
