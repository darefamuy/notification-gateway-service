package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * SMS adapter backed by Africa's Talking.
 *
 * <p>Africa's Talking (AT) is the leading messaging API provider across
 * sub-Saharan Africa, with direct carrier relationships in Nigeria (MTN,
 * Airtel, Glo, 9mobile), Ghana, Kenya, and 15+ other markets. For a Nigerian
 * bank this is the recommended primary SMS provider due to:
 * <ul>
 *   <li>Direct SMSC connections to Nigerian carriers (lower latency, higher delivery)</li>
 *   <li>Registered sender ID support ("ABBANK")</li>
 *   <li>Per-message delivery receipts via webhook</li>
 *   <li>NGN billing — no FX exposure</li>
 *   <li>DND (Do Not Disturb) list management to maintain regulatory compliance</li>
 * </ul>
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code AFRICAS_TALKING_API_KEY}</li>
 *   <li>{@code AFRICAS_TALKING_USERNAME} — your AT application username</li>
 * </ul>
 *
 * <p>API reference:
 * <a href="https://developers.africastalking.com/docs/sms/sending">AT SMS API</a>
 */
public class AfricasTalkingSmsAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(AfricasTalkingSmsAdapter.class);

    // AT uses different base URLs for live vs sandbox
    private static final String LIVE_URL    = "https://api.africastalking.com/version1/messaging";
    private static final String SANDBOX_URL = "https://api.sandbox.africastalking.com/version1/messaging";

    private final String apiKey;
    private final String username;
    private final String senderId;
    private final boolean sandbox;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AfricasTalkingSmsAdapter(
            final String apiKey,
            final String username,
            final String senderId,
            final boolean sandbox) {
        this.apiKey    = apiKey;
        this.username  = username;
        this.senderId  = senderId;
        this.sandbox   = sandbox;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "africas-talking"; }
    @Override public String channelType()  { return "SMS"; }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
            && username != null && !username.isBlank();
    }

    @Override
    public DeliveryResult send(final NotificationEvent event, final CustomerProfile profile) {
        if (!profile.hasPhone()) {
            return DeliveryResult.builder(providerName(), channelType())
                    .skipped("Customer " + profile.getCustomerId() + " has no phone number")
                    .build();
        }

        final String smsText  = buildSmsText(event);
        final String endpoint = sandbox ? SANDBOX_URL : LIVE_URL;

        // AT uses application/x-www-form-urlencoded
        final FormBody formBody = new FormBody.Builder()
                .add("username", username)
                .add("to",       profile.getPhone())
                .add("message",  smsText)
                .add("from",     senderId)
                .build();

        final Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("apiKey",  apiKey)
                .addHeader("Accept",  "application/json")
                .post(formBody)
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int    code     = response.code();
            final String respBody = response.body() != null ? response.body().string() : "";

            if (code == 201) {
                // Parse the AT SMSMessageData response
                final String msgId    = extractMessageId(respBody);
                final String status   = extractStatus(respBody);
                final boolean success = "Success".equalsIgnoreCase(status);

                if (success) {
                    LOG.info("AT SMS sent: notificationId={} to={} msgId={} {}",
                            event.getNotificationId(), maskPhone(profile.getPhone()), msgId,
                            sandbox ? "[SANDBOX]" : "");
                    return DeliveryResult.builder(providerName(), channelType())
                            .success(msgId, code).build();
                } else {
                    LOG.warn("AT SMS delivery failed: status={} body={}", status, respBody);
                    return DeliveryResult.builder(providerName(), channelType())
                            .failure("AT status: " + status, code).build();
                }
            } else {
                LOG.warn("AT API error: http={} body={}", code, respBody);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + respBody, code).build();
            }
        } catch (IOException e) {
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    /**
     * Builds an SMS-optimised message (max 160 chars for single segment).
     * Truncates body if necessary, always preserving the intro line.
     */
    private String buildSmsText(final NotificationEvent event) {
        // Extract first meaningful line from the body as the SMS text
        final String body = event.getBody() != null ? event.getBody() : event.getSubject();
        // Use the subject as a concise intro, then truncate body
        final String sms = "AB Bank: " + (event.getSubject() != null ? event.getSubject() : "")
                + ". " + body;
        // Hard limit: 160 chars for a single GSM-7 SMS segment
        return sms.length() <= 160 ? sms : sms.substring(0, 157) + "...";
    }

    private String extractMessageId(final String body) {
        try {
            return mapper.readTree(body)
                    .path("SMSMessageData")
                    .path("Recipients")
                    .path(0)
                    .path("messageId")
                    .asText("unknown");
        } catch (Exception e) { return "unknown"; }
    }

    private String extractStatus(final String body) {
        try {
            return mapper.readTree(body)
                    .path("SMSMessageData")
                    .path("Recipients")
                    .path(0)
                    .path("status")
                    .asText("Unknown");
        } catch (Exception e) { return "Unknown"; }
    }

    private String maskPhone(final String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 6) + "***";
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
