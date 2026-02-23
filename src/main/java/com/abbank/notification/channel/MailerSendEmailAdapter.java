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
 * Email adapter backed by MailerSend.
 *
 * <p>MailerSend offers a generous free tier (12,000 emails/month),
 * excellent deliverability analytics, and a simple REST API — making it
 * a practical alternative to SendGrid for smaller volumes or as a fallback.
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code MAILERSEND_API_KEY} — an API token from the MailerSend dashboard</li>
 * </ul>
 *
 * <p>API reference:
 * <a href="https://developers.mailersend.com/api/v1/email.html">MailerSend Email API</a>
 */
public class MailerSendEmailAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MailerSendEmailAdapter.class);
    private static final String ENDPOINT = "https://api.mailersend.com/v1/email";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String fromAddress;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MailerSendEmailAdapter(final String apiKey, final String fromAddress) {
        this.apiKey      = apiKey;
        this.fromAddress = fromAddress;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "mailersend"; }
    @Override public String channelType()  { return "EMAIL"; }

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
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int code = response.code();
            // MailerSend returns 202 on success, message ID in X-Message-Id header
            if (code == 202) {
                final String msgId = response.header("X-Message-Id", "unknown");
                LOG.info("MailerSend email sent: notificationId={} msgId={}",
                        event.getNotificationId(), msgId);
                return DeliveryResult.builder(providerName(), channelType())
                        .success(msgId, code).build();
            } else {
                final String body = response.body() != null ? response.body().string() : "(empty)";
                LOG.warn("MailerSend rejected: http={} body={}", code, body);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + body, code).build();
            }
        } catch (IOException e) {
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    private String buildPayload(final NotificationEvent event, final CustomerProfile profile) {
        final ObjectNode root = mapper.createObjectNode();

        // From
        final ObjectNode from = root.putObject("from");
        from.put("email", fromAddress);
        from.put("name",  "AB Bank");

        // To
        final ArrayNode to = root.putArray("to");
        final ObjectNode toEntry = to.addObject();
        toEntry.put("email", profile.getEmail());
        toEntry.put("name",  profile.getFullName());

        root.put("subject", event.getSubject());

        // Plain text body
        root.put("text", event.getBody());

        // Variables for template substitution (if using MailerSend templates)
        final ArrayNode variables = root.putArray("variables");
        final ObjectNode varEntry = variables.addObject();
        varEntry.put("email", profile.getEmail());
        final ArrayNode subs = varEntry.putArray("substitutions");
        addSub(subs, "name",    profile.getFirstName());
        addSub(subs, "body",    event.getBody());
        addSub(subs, "subject", event.getSubject());

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize MailerSend payload", e);
        }
    }

    private void addSub(final ArrayNode arr, final String var, final String value) {
        final ObjectNode node = arr.addObject();
        node.put("var",   var);
        node.put("value", value != null ? value : "");
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
