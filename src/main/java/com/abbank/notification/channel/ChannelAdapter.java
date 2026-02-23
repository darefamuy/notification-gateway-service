package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;

/**
 * Pluggable notification channel adapter.
 *
 * <p>Each concrete implementation wraps a single external provider
 * (SendGrid, SES, Twilio, Africa's Talking, etc.) and translates a
 * {@link NotificationEvent} into that provider's wire format.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Implementations must be thread-safe; a single instance is shared
 *       across all consumer threads.</li>
 *   <li>Implementations must <em>never</em> throw unchecked exceptions —
 *       all errors must be captured in a {@link DeliveryResult} with
 *       {@code Status.FAILURE}. The retry policy is applied by the
 *       {@link com.abbank.notification.retry.RetryExecutor} above this layer.</li>
 *   <li>Implementations must close their HTTP clients when {@link #close()} is called.</li>
 * </ul>
 */
public interface ChannelAdapter extends AutoCloseable {

    /**
     * Human-readable provider name for logging and metrics (e.g. "sendgrid", "twilio").
     */
    String providerName();

    /**
     * The channel this adapter handles: "EMAIL" or "SMS".
     */
    String channelType();

    /**
     * Send a notification to the given customer via this channel.
     *
     * @param event   the notification to send
     * @param profile resolved customer contact details
     * @return a {@link DeliveryResult} describing the outcome; never null
     */
    DeliveryResult send(NotificationEvent event, CustomerProfile profile);

    /**
     * Returns {@code true} if this adapter has the credentials and
     * configuration required to operate. Called during startup to fail fast.
     */
    boolean isConfigured();

    @Override
    void close();
}
