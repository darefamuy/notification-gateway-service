package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Email adapter backed by the SendGrid v3 Mail Send API.
 *
 * <p>API reference: <a href="https://docs.sendgrid.com/api-reference/mail-send/mail-send">
 * SendGrid Mail Send v3</a>
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code SENDGRID_API_KEY} — a SendGrid API key with "Mail Send" permission</li>
 * </ul>
 */
public class SendGridEmailAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SendGridEmailAdapter.class);
    private static final String DEFAULT_ENDPOINT = "https://api.sendgrid.com/v3/mail/send";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String fromAddress;
    private final String replyTo;
    private final String endpoint;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SendGridEmailAdapter(
            final String apiKey,
            final String fromAddress,
            final String replyTo) {
        this(apiKey, fromAddress, replyTo, DEFAULT_ENDPOINT);
    }

    public SendGridEmailAdapter(
            final String apiKey,
            final String fromAddress,
            final String replyTo,
            final String endpoint) {
        this.apiKey      = apiKey;
        this.fromAddress = fromAddress;
        this.replyTo     = replyTo;
        this.endpoint    = endpoint;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String providerName() { return "sendgrid"; }

    @Override
    public String channelType() { return "EMAIL"; }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public DeliveryResult send(final NotificationEvent event, final CustomerProfile profile) {
        if (!profile.hasEmail()) {
            return DeliveryResult.builder(providerName(), channelType())
                    .skipped("Customer " + profile.getCustomerId() + " has no email address")
                    .build();
        }

        final String payload = buildPayload(event, profile);

        final Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int code = response.code();
            // SendGrid returns 202 Accepted on success
            if (code == 202) {
                // The message ID is in the X-Message-Id response header
                final String msgId = response.header("X-Message-Id", "unknown");
                LOG.info("SendGrid email sent: notificationId={} to={} msgId={} subject={}",
                        event.getNotificationId(), maskEmail(profile.getEmail()), msgId, event.getSubject());
                return DeliveryResult.builder(providerName(), channelType())
                        .success(msgId, code)
                        .build();
            } else {
                final String body = response.body() != null ? response.body().string() : "(empty)";
                LOG.warn("SendGrid rejected email: notificationId={} http={} body={}",
                        event.getNotificationId(), code, body);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + body, code)
                        .build();
            }
        } catch (IOException e) {
            LOG.error("SendGrid IO error: notificationId={} error={}",
                    event.getNotificationId(), e.getMessage());
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0)
                    .build();
        }
    }

    private String buildPayload(final NotificationEvent event, final CustomerProfile profile) {
        // SendGrid v3 JSON structure
        // https://docs.sendgrid.com/api-reference/mail-send/mail-send#request-body
        final ObjectNode root = mapper.createObjectNode();

        // Personalizations array (supports per-recipient overrides)
        final ArrayNode personalizations = root.putArray("personalizations");
        final ObjectNode personalization = personalizations.addObject();
        final ArrayNode  toList = personalization.putArray("to");
        final ObjectNode toEntry = toList.addObject();
        toEntry.put("email", profile.getEmail());
        toEntry.put("name",  profile.getFullName());

        // From
        final ObjectNode from = root.putObject("from");
        from.put("email", fromAddress);
        from.put("name",  "AB Bank");

        // Reply-To (optional)
        if (replyTo != null && !replyTo.isBlank()) {
            final ObjectNode replyToNode = root.putObject("reply_to");
            replyToNode.put("email", replyTo);
        }

        root.put("subject", event.getSubject());

        // Content: plain text + HTML
        final ArrayNode content = root.putArray("content");
        final ObjectNode textContent = content.addObject();
        textContent.put("type",  "text/plain");
        textContent.put("value", event.getBody());

        final ObjectNode htmlContent = content.addObject();
        htmlContent.put("type",  "text/html");
        htmlContent.put("value", buildHtml(event, profile));

        // Custom args for audit trail
        final ObjectNode customArgs = root.putObject("custom_args");
        customArgs.put("notificationId",   event.getNotificationId());
        customArgs.put("notificationType", event.getNotificationType() != null
                ? event.getNotificationType().name() : "UNKNOWN");
        customArgs.put("accountId",        String.valueOf(event.getAccountId()));

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SendGrid payload", e);
        }
    }

    private String buildHtml(final NotificationEvent event, final CustomerProfile profile) {
        final String color = severityColor(event.getSeverity());
        return "<html><body style=\"font-family:Arial,sans-serif;max-width:600px;margin:0 auto;\">"
             + "<div style=\"background:" + color + ";color:white;padding:16px;border-radius:4px 4px 0 0;\">"
             + "<h2 style=\"margin:0;\">" + escapeHtml(event.getSubject()) + "</h2>"
             + "</div>"
             + "<div style=\"padding:24px;background:#f9f9f9;\">"
             + "<p>Dear " + escapeHtml(profile.getFirstName()) + ",</p>"
             + "<p style=\"white-space:pre-line;\">" + escapeHtml(event.getBody()) + "</p>"
             + "</div>"
             + "<div style=\"padding:12px 24px;font-size:12px;color:#666;\">"
             + "<p>This is an automated message from AB Bank. Please do not reply to this email.</p>"
             + "<p>If you did not initiate this activity, contact us immediately at <a href=\"tel:+2341234567890\">+234 123 456 7890</a>.</p>"
             + "</div>"
             + "</body></html>";
    }

    private String severityColor(final NotificationEvent.Severity severity) {
        if (severity == null) return "#1a5276";
        return switch (severity) {
            case CRITICAL -> "#922b21";
            case HIGH     -> "#c0392b";
            case MEDIUM   -> "#d97706";
            case LOW      -> "#1a5276";
        };
    }

    private String escapeHtml(final String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    private String maskEmail(final String email) {
        if (email == null) return "null";
        final int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.substring(0, Math.min(3, at)) + "***" + email.substring(at);
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
