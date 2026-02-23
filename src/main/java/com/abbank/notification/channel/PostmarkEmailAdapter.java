package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Email adapter backed by Postmark.
 *
 * <p>Postmark specialises in transactional email with industry-leading inbox
 * placement rates — particularly suited for banking alerts, OTPs, and
 * account notifications where deliverability directly impacts customer trust.
 * It provides per-message open/click/bounce analytics and dedicated IP pools.
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code POSTMARK_SERVER_TOKEN} — the server API token from the Postmark dashboard</li>
 * </ul>
 *
 * <p>The sender address must have a verified Sender Signature in Postmark.
 *
 * <p>API reference:
 * <a href="https://postmarkapp.com/developer/api/email-api">Postmark Email API</a>
 */
public class PostmarkEmailAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(PostmarkEmailAdapter.class);
    private static final String ENDPOINT = "https://api.postmarkapp.com/email";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String serverToken;
    private final String fromAddress;
    private final String messageStream;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PostmarkEmailAdapter(
            final String serverToken,
            final String fromAddress,
            final String messageStream) {
        this.serverToken   = serverToken;
        this.fromAddress   = fromAddress;
        this.messageStream = messageStream != null ? messageStream : "outbound";
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "postmark"; }
    @Override public String channelType()  { return "EMAIL"; }

    @Override
    public boolean isConfigured() {
        return serverToken != null && !serverToken.isBlank();
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
                .url(ENDPOINT)
                .addHeader("Accept",           "application/json")
                .addHeader("Content-Type",     "application/json")
                .addHeader("X-Postmark-Server-Token", serverToken)
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int    code     = response.code();
            final String respBody = response.body() != null ? response.body().string() : "";

            if (code == 200) {
                final String msgId = extractMessageId(respBody);
                LOG.info("Postmark email sent: notificationId={} msgId={}",
                        event.getNotificationId(), msgId);
                return DeliveryResult.builder(providerName(), channelType())
                        .success(msgId, code).build();
            } else {
                LOG.warn("Postmark rejected: http={} body={}", code, respBody);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + respBody, code).build();
            }
        } catch (IOException e) {
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    private String buildPayload(final NotificationEvent event, final CustomerProfile profile) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("From",          "AB Bank <" + fromAddress + ">");
        root.put("To",            profile.getFullName() + " <" + profile.getEmail() + ">");
        root.put("Subject",       event.getSubject());
        root.put("TextBody",      event.getBody());
        root.put("MessageStream", messageStream);
        // Tag for Postmark analytics grouping
        root.put("Tag", event.getNotificationType() != null
                ? event.getNotificationType().name().toLowerCase().replace('_', '-')
                : "alert");
        // Metadata visible in the Postmark activity feed
        final ObjectNode meta = root.putObject("Metadata");
        meta.put("notificationId",   event.getNotificationId());
        meta.put("accountId",        String.valueOf(event.getAccountId()));
        meta.put("notificationType", event.getNotificationType() != null
                ? event.getNotificationType().name() : "UNKNOWN");
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Postmark payload", e);
        }
    }

    private String extractMessageId(final String body) {
        try {
            return mapper.readTree(body).path("MessageID").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
