package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * SMS adapter backed by Twilio Programmable SMS.
 *
 * <p>Twilio provides global SMS coverage including Nigeria, with support for
 * alphanumeric sender IDs in supported markets and a mature REST API.
 * Recommended as a secondary (fallback) SMS provider behind Africa's Talking
 * for Nigerian traffic, or as the primary provider for international customers.
 *
 * <h2>Required credentials</h2>
 * <ul>
 *   <li>{@code TWILIO_ACCOUNT_SID}</li>
 *   <li>{@code TWILIO_AUTH_TOKEN}</li>
 *   <li>{@code TWILIO_FROM_NUMBER} — your Twilio phone number in E.164 format,
 *       e.g. {@code +12025551234}, or a registered alphanumeric sender ID</li>
 * </ul>
 *
 * <p>API reference:
 * <a href="https://www.twilio.com/docs/sms/api/message-resource#create-a-message-resource">
 * Twilio Messages Resource</a>
 */
public class TwilioSmsAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(TwilioSmsAdapter.class);

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TwilioSmsAdapter(
            final String accountSid,
            final String authToken,
            final String fromNumber) {
        this.accountSid = accountSid;
        this.authToken  = authToken;
        this.fromNumber = fromNumber;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "twilio"; }
    @Override public String channelType()  { return "SMS"; }

    @Override
    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
            && authToken  != null && !authToken.isBlank()
            && fromNumber != null && !fromNumber.isBlank();
    }

    @Override
    public DeliveryResult send(final NotificationEvent event, final CustomerProfile profile) {
        if (!profile.hasPhone()) {
            return DeliveryResult.builder(providerName(), channelType())
                    .skipped("Customer " + profile.getCustomerId() + " has no phone number")
                    .build();
        }

        final String endpoint = "https://api.twilio.com/2010-04-01/Accounts/"
                + accountSid + "/Messages.json";

        // Twilio uses HTTP Basic Auth: AccountSid:AuthToken
        final String credentials = Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

        final FormBody formBody = new FormBody.Builder()
                .add("To",   profile.getPhone())
                .add("From", fromNumber)
                .add("Body", buildSmsText(event))
                .build();

        final Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Basic " + credentials)
                .post(formBody)
                .build();

        try (Response response = http.newCall(request).execute()) {
            final int    code     = response.code();
            final String respBody = response.body() != null ? response.body().string() : "";

            // Twilio returns 201 Created on success
            if (code == 201) {
                final String sid = extractSid(respBody);
                LOG.info("Twilio SMS sent: notificationId={} to={} sid={}",
                        event.getNotificationId(), maskPhone(profile.getPhone()), sid);
                return DeliveryResult.builder(providerName(), channelType())
                        .success(sid, code).build();
            } else {
                LOG.warn("Twilio rejected SMS: http={} body={}", code, respBody);
                return DeliveryResult.builder(providerName(), channelType())
                        .failure("HTTP " + code + ": " + respBody, code).build();
            }
        } catch (IOException e) {
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    private String buildSmsText(final NotificationEvent event) {
        final String body    = event.getBody() != null ? event.getBody() : "";
        final String subject = event.getSubject() != null ? event.getSubject() : "";
        final String sms     = "AB Bank: " + subject + ". " + body;
        return sms.length() <= 160 ? sms : sms.substring(0, 157) + "...";
    }

    private String extractSid(final String body) {
        try {
            return mapper.readTree(body).path("sid").asText("unknown");
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
