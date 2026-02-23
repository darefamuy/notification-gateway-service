package com.abbank.notification.consumer;

import com.abbank.notification.channel.ChannelAdapter;
import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.abbank.notification.retry.RetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes a {@link NotificationEvent} to the appropriate channel adapters.
 *
 * <h2>Routing logic</h2>
 * <ol>
 *   <li>Determine effective channel: if the event's {@code severity} is in the
 *       "force-both" list (CRITICAL, HIGH), both EMAIL and SMS are sent regardless
 *       of the event's {@code channel} field.</li>
 *   <li>For each required channel, iterate the ordered adapter list.
 *       The first adapter that returns {@link DeliveryResult.Status#SUCCESS}
 *       wins — subsequent adapters for the same channel are <em>not</em> called.</li>
 *   <li>Each adapter call is wrapped by the {@link RetryExecutor} for
 *       transient error handling.</li>
 * </ol>
 */
public class NotificationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<ChannelAdapter> emailAdapters;
    private final List<ChannelAdapter> smsAdapters;
    private final RetryExecutor        retry;
    private final List<String>         forceBothSeverities;

    public NotificationDispatcher(
            final List<ChannelAdapter> emailAdapters,
            final List<ChannelAdapter> smsAdapters,
            final RetryExecutor retry,
            final GatewayConfig config) {
        this.emailAdapters       = List.copyOf(emailAdapters);
        this.smsAdapters         = List.copyOf(smsAdapters);
        this.retry               = retry;
        this.forceBothSeverities = config.getForceBothOnSeverity();
    }

    /**
     * Dispatch {@code event} to all applicable channels.
     *
     * @return list of all {@link DeliveryResult}s from every dispatch attempt
     */
    public List<DeliveryResult> dispatch(
            final NotificationEvent event,
            final CustomerProfile profile) {

        final List<DeliveryResult> results = new ArrayList<>();
        final boolean sendEmail = shouldSendEmail(event);
        final boolean sendSms   = shouldSendSms(event);

        if (sendEmail) {
            results.add(dispatchToChannel(event, profile, emailAdapters, "EMAIL"));
        }
        if (sendSms) {
            results.add(dispatchToChannel(event, profile, smsAdapters, "SMS"));
        }

        if (!sendEmail && !sendSms) {
            LOG.warn("No channel selected for event: notificationId={} channel={} severity={}",
                    event.getNotificationId(), event.getChannel(), event.getSeverity());
        }

        return results;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DeliveryResult dispatchToChannel(
            final NotificationEvent event,
            final CustomerProfile profile,
            final List<ChannelAdapter> adapters,
            final String channelName) {

        if (adapters.isEmpty()) {
            LOG.warn("No {} adapters configured — skipping channel for notificationId={}",
                    channelName, event.getNotificationId());
            return DeliveryResult.builder("none", channelName)
                    .skipped("No " + channelName + " adapters configured")
                    .build();
        }

        DeliveryResult lastResult = null;

        for (final ChannelAdapter adapter : adapters) {
            final String desc = adapter.providerName() + "/" + adapter.channelType()
                    + " notificationId=" + event.getNotificationId();

            lastResult = retry.execute(() -> adapter.send(event, profile), desc);

            if (lastResult.isSuccess()) {
                return lastResult; // first success wins — skip remaining adapters
            }

            // SKIPPED is permanent (no phone/email) — no point trying next adapter
            if (lastResult.getStatus() == DeliveryResult.Status.SKIPPED) {
                return lastResult;
            }

            // FAILURE after retries — try next adapter (fallback)
            LOG.warn("{} failed after retries, trying next adapter for notificationId={}",
                    adapter.providerName(), event.getNotificationId());
        }

        // All adapters exhausted
        LOG.error("All {} adapters failed for notificationId={}", channelName, event.getNotificationId());
        return lastResult;
    }

    private boolean shouldSendEmail(final NotificationEvent event) {
        if (isForceBoth(event)) return true;
        return event.getChannel() == NotificationEvent.Channel.EMAIL
            || event.getChannel() == NotificationEvent.Channel.BOTH;
    }

    private boolean shouldSendSms(final NotificationEvent event) {
        if (isForceBoth(event)) return true;
        return event.getChannel() == NotificationEvent.Channel.SMS
            || event.getChannel() == NotificationEvent.Channel.BOTH;
    }

    private boolean isForceBoth(final NotificationEvent event) {
        if (event.getSeverity() == null) return false;
        return forceBothSeverities.contains(event.getSeverity().name());
    }
}
