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
 * SMS adapter backed by Termii.
 *
 * <p>Termii is a Lagos-headquartered CPaaS provider with native integrations
 * with all four major Nigerian mobile networks. Key advantages for AB Bank:
 * <ul>
 *   <li><strong>DND compliance:</strong> Termii routes through the NCC-approved
 *       DND bypass route for transactional banking messages, ensuring delivery
 *       even to customers on the Do-Not-Disturb registry.</li>
 *   <li><strong>Naira billing:</strong> Priced in NGN, no foreign exchange exposure.</li>
 *   <li><strong>WhatsApp channel:</strong> Supports fallback from SMS → WhatsApp
 *       for richer message formats (set {@code channel = "WhatsApp"}).</li>
 *   <li><strong>OTP channel:</strong> Dedicated high-priority route for one-time
 *       passwords with sub-5-second delivery SLA.</li>
 * </ul>
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code TERMII_API_KEY} — from the Termii developer dashboard</li>
 * </ul>
 *
 * <p>API reference:
 * <a href="https://developers.termii.com/messaging">Termii Messaging API</a>
 */
public class TermiiSmsAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(TermiiSmsAdapter.class);
    private static final String ENDPOINT = "https://v3.api.termii.com/api/sms/send";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String senderId;
    private final String channel; // "generic" | "dnd" | "WhatsApp"
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TermiiSmsAdapter(
            final String apiKey,
            final String senderId,
            final String channel) {
        this.apiKey    = apiKey;
        this.senderId  = senderId;
        this.channel   = channel != null ? channel : "generic";
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "termii"; }
    @Override public String channelType()  { return "SMS"; }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public DeliveryResult send(final NotificationEvent event, final CustomerProfile profile) {
        if (!profile.hasPhone()) {
            return DeliveryResult.builder(providerName(), channelType())
                    .skipped("Customer " + profile.getCustomerId() + " has no phone number")
                    .build();
        }

        final String payload = buildPayload(event, profile);
        final Request request = new Request.Builder()
                .url(ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int    code     = response.code();
            final String respBody = response.body() != null ? response.body().string() : "";

            if (code == 200) {
                final String msgId  = extractMessageId(respBody);
                final String status = extractStatus(respBody);
                LOG.info("Termii SMS sent: notificationId={} to={} msgId={} status={}",
                        event.getNotificationId(), maskPhone(profile.getPhone()), msgId, status);
                return DeliveryResult.builder(providerName(), channelType())
                        .success(msgId, code).build();
            } else {
                LOG.warn("Termii rejected SMS: http={} body={}", code, respBody);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + respBody, code).build();
            }
        } catch (IOException e) {
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    private String buildPayload(final NotificationEvent event, final CustomerProfile profile) {
        // Termii v3 messaging payload
        // https://developers.termii.com/messaging
        final ObjectNode root = mapper.createObjectNode();
        root.put("api_key",  apiKey);
        root.put("to",       profile.getPhone());
        root.put("from",     senderId);
        root.put("sms",      buildSmsText(event));
        root.put("type",     "plain");
        root.put("channel",  channel);  // "generic" uses DND-bypass route for banking
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Termii payload", e);
        }
    }

    private String buildSmsText(final NotificationEvent event) {
        final String subject = event.getSubject() != null ? event.getSubject() : "";
        final String body    = event.getBody()    != null ? event.getBody()    : "";
        final String sms     = "AB Bank: " + subject + ". " + body;
        return sms.length() <= 160 ? sms : sms.substring(0, 157) + "...";
    }

    private String extractMessageId(final String body) {
        try {
            return mapper.readTree(body).path("message_id").asText("unknown");
        } catch (Exception e) { return "unknown"; }
    }

    private String extractStatus(final String body) {
        try {
            return mapper.readTree(body).path("message").asText("unknown");
        } catch (Exception e) { return "unknown"; }
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
