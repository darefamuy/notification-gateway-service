package com.abbank.notification.model;

import java.time.Instant;

/**
 * Immutable result of a single notification dispatch attempt.
 *
 * <p>Returned by every {@link com.abbank.notification.channel.ChannelAdapter}
 * and included in audit logs. The {@code providerMessageId} is the external
 * reference ID returned by the provider (e.g. SendGrid message ID, Twilio SID).
 */
public final class DeliveryResult {

    public enum Status { SUCCESS, FAILURE, SKIPPED }

    private final Status  status;
    private final String  provider;
    private final String  channel;           // "EMAIL" or "SMS"
    private final String  providerMessageId; // null on failure/skip
    private final String  errorMessage;      // null on success
    private final int     httpStatusCode;    // 0 if not applicable
    private final Instant deliveredAt;

    private DeliveryResult(final Builder b) {
        this.status            = b.status;
        this.provider          = b.provider;
        this.channel           = b.channel;
        this.providerMessageId = b.providerMessageId;
        this.errorMessage      = b.errorMessage;
        this.httpStatusCode    = b.httpStatusCode;
        this.deliveredAt       = Instant.now();
    }

    public static Builder builder(final String provider, final String channel) {
        return new Builder(provider, channel);
    }

    public static final class Builder {
        private final String provider;
        private final String channel;
        private Status status = Status.FAILURE;
        private String providerMessageId;
        private String errorMessage;
        private int    httpStatusCode;

        private Builder(final String provider, final String channel) {
            this.provider = provider;
            this.channel  = channel;
        }

        public Builder success(final String messageId, final int httpCode) {
            this.status            = Status.SUCCESS;
            this.providerMessageId = messageId;
            this.httpStatusCode    = httpCode;
            return this;
        }

        public Builder failure(final String error, final int httpCode) {
            this.status         = Status.FAILURE;
            this.errorMessage   = error;
            this.httpStatusCode = httpCode;
            return this;
        }

        public Builder skipped(final String reason) {
            this.status       = Status.SKIPPED;
            this.errorMessage = reason;
            return this;
        }

        public DeliveryResult build() { return new DeliveryResult(this); }
    }

    public Status  getStatus()            { return status; }
    public String  getProvider()          { return provider; }
    public String  getChannel()           { return channel; }
    public String  getProviderMessageId() { return providerMessageId; }
    public String  getErrorMessage()      { return errorMessage; }
    public int     getHttpStatusCode()    { return httpStatusCode; }
    public Instant getDeliveredAt()       { return deliveredAt; }
    public boolean isSuccess()            { return status == Status.SUCCESS; }

    @Override
    public String toString() {
        return "DeliveryResult{provider=" + provider
             + ", channel=" + channel
             + ", status=" + status
             + (providerMessageId != null ? ", msgId=" + providerMessageId : "")
             + (errorMessage != null ? ", error=" + errorMessage : "")
             + (httpStatusCode > 0 ? ", http=" + httpStatusCode : "")
             + "}";
    }
}
