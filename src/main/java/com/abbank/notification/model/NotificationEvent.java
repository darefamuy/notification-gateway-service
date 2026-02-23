package com.abbank.notification.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical notification event consumed from the AB Bank notification topics.
 *
 * <p>This is a read-only mirror of the model produced by {@code abbank-streams}.
 * Fields map 1:1; only deserialization is needed here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public enum NotificationType {
        FRAUD_ALERT,
        HIGH_VALUE_ALERT,
        BALANCE_UPDATE,
        DORMANCY_ALERT,
        DAILY_SPEND_SUMMARY
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Channel  { EMAIL, SMS, BOTH }

    private String            notificationId;
    private NotificationType  notificationType;
    private Severity          severity;
    private Channel           channel;
    private Long              accountId;
    private Long              customerId;
    private String            accountNumber;
    private String            subject;
    private String            body;
    private Instant           eventTime;
    private Instant           generatedAt;
    private Map<String, Object> metadata;

    // Jackson requires a no-arg constructor for deserialization
    public NotificationEvent() { }

    public static NotificationEvent fromJson(final String json) {
        try {
            return MAPPER.readValue(json, NotificationEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialise NotificationEvent: " + e.getMessage(), e);
        }
    }

    public String getNotificationId()     { return notificationId; }
    public NotificationType getNotificationType() { return notificationType; }
    public Severity getSeverity()         { return severity; }
    public Channel  getChannel()          { return channel; }
    public Long     getAccountId()        { return accountId; }
    public Long     getCustomerId()       { return customerId; }
    public String   getAccountNumber()    { return accountNumber; }
    public String   getSubject()          { return subject; }
    public String   getBody()             { return body; }
    public Instant  getEventTime()        { return eventTime; }
    public Instant  getGeneratedAt()      { return generatedAt; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setNotificationId(String v)      { this.notificationId = v; }
    public void setNotificationType(NotificationType v) { this.notificationType = v; }
    public void setSeverity(Severity v)          { this.severity = v; }
    public void setChannel(Channel v)            { this.channel = v; }
    public void setAccountId(Long v)             { this.accountId = v; }
    public void setCustomerId(Long v)            { this.customerId = v; }
    public void setAccountNumber(String v)       { this.accountNumber = v; }
    public void setSubject(String v)             { this.subject = v; }
    public void setBody(String v)                { this.body = v; }
    public void setEventTime(Instant v)          { this.eventTime = v; }
    public void setGeneratedAt(Instant v)        { this.generatedAt = v; }
    public void setMetadata(Map<String, Object> v) { this.metadata = v; }

    @Override
    public String toString() {
        return "NotificationEvent{id=" + notificationId
             + ", type=" + notificationType
             + ", severity=" + severity
             + ", accountId=" + accountId + "}";
    }
}
