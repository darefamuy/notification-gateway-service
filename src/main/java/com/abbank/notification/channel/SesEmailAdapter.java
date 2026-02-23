package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Email adapter backed by AWS Simple Email Service (SES) v2.
 *
 * <p>Uses the SES v2 {@code SendEmail} REST API with AWS Signature Version 4
 * request signing, so no AWS SDK dependency is needed.
 *
 * <h2>Required environment variables</h2>
 * <ul>
 *   <li>{@code AWS_ACCESS_KEY_ID}</li>
 *   <li>{@code AWS_SECRET_ACCESS_KEY}</li>
 *   <li>{@code AWS_REGION} (e.g. {@code eu-west-1})</li>
 * </ul>
 *
 * <p>The sender address ({@code from}) must be verified in the SES console.
 *
 * <p>API reference:
 * <a href="https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html">SES v2 SendEmail</a>
 */
public class SesEmailAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SesEmailAdapter.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter ISO_DATE     = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final String fromAddress;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SesEmailAdapter(
            final String accessKeyId,
            final String secretAccessKey,
            final String region,
            final String fromAddress) {
        this.accessKeyId     = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region          = region;
        this.fromAddress     = fromAddress;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override public String providerName() { return "ses"; }
    @Override public String channelType()  { return "EMAIL"; }

    @Override
    public boolean isConfigured() {
        return accessKeyId != null && !accessKeyId.isBlank()
            && secretAccessKey != null && !secretAccessKey.isBlank()
            && region != null && !region.isBlank();
    }

    @Override
    public DeliveryResult send(final NotificationEvent event, final CustomerProfile profile) {
        if (!profile.hasEmail()) {
            return DeliveryResult.builder(providerName(), channelType())
                    .skipped("Customer " + profile.getCustomerId() + " has no email address")
                    .build();
        }

        final String url     = "https://email." + region + ".amazonaws.com/v2/email/outbound-emails";
        final String payload = buildPayload(event, profile);

        try {
            final ZonedDateTime now        = ZonedDateTime.now(ZoneOffset.UTC);
            final String        dateTime   = ISO_DATETIME.format(now);
            final String        date       = ISO_DATE.format(now);
            final String        payloadHash = sha256Hex(payload);
            final String        host        = "email." + region + ".amazonaws.com";

            // Build the canonical request for SigV4
            final String canonicalRequest = "POST\n"
                    + "/v2/email/outbound-emails\n"
                    + "\n"
                    + "content-type:application/json; charset=utf-8\n"
                    + "host:" + host + "\n"
                    + "x-amz-date:" + dateTime + "\n"
                    + "\n"
                    + "content-type;host;x-amz-date\n"
                    + payloadHash;

            final String credentialScope = date + "/" + region + "/ses/aws4_request";
            final String stringToSign = "AWS4-HMAC-SHA256\n"
                    + dateTime + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            final byte[] signingKey = getSigningKey(secretAccessKey, date, region, "ses");
            final String signature  = hmacHex(signingKey, stringToSign);

            final String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope
                    + ", SignedHeaders=content-type;host;x-amz-date"
                    + ", Signature=" + signature;

            final Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization",   authHeader)
                    .addHeader("Content-Type",    "application/json; charset=utf-8")
                    .addHeader("X-Amz-Date",      dateTime)
                    .post(RequestBody.create(payload, JSON))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                final int    code = response.code();
                final String body = response.body() != null ? response.body().string() : "";
                if (code == 200) {
                    final String msgId = extractMessageId(body);
                    LOG.info("SES email sent: notificationId={} to={} msgId={}",
                            event.getNotificationId(), maskEmail(profile.getEmail()), msgId);
                    return DeliveryResult.builder(providerName(), channelType())
                            .success(msgId, code).build();
                } else {
                    LOG.warn("SES rejected email: notificationId={} http={} body={}",
                            event.getNotificationId(), code, body);
                    return DeliveryResult.builder(providerName(), channelType())
                            .failure("HTTP " + code + ": " + body, code).build();
                }
            }
        } catch (Exception e) {
            LOG.error("SES error: notificationId={} error={}",
                    event.getNotificationId(), e.getMessage(), e);
            return DeliveryResult.builder(providerName(), channelType())
                    .failure(e.getMessage(), 0).build();
        }
    }

    private String buildPayload(final NotificationEvent event, final CustomerProfile profile) {
        // SES v2 SendEmail request body
        // https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html
        final ObjectNode root = mapper.createObjectNode();

        final ObjectNode from = root.putObject("FromEmailAddress");
        // SES v2 uses a flat string for FromEmailAddress
        root.put("FromEmailAddress", "AB Bank <" + fromAddress + ">");

        final ObjectNode dest = root.putObject("Destination");
        dest.putArray("ToAddresses").add(profile.getFullName() + " <" + profile.getEmail() + ">");

        final ObjectNode content = root.putObject("Content");
        final ObjectNode simple  = content.putObject("Simple");

        final ObjectNode subject = simple.putObject("Subject");
        subject.put("Data",    event.getSubject());
        subject.put("Charset", "UTF-8");

        final ObjectNode body = simple.putObject("Body");
        final ObjectNode text = body.putObject("Text");
        text.put("Data",    event.getBody());
        text.put("Charset", "UTF-8");

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SES payload", e);
        }
    }

    // ── AWS SigV4 helpers ─────────────────────────────────────────────────────

    private static String sha256Hex(final String data) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(final byte[] key, final String data) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(final byte[] key, final String data) throws Exception {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    private static byte[] getSigningKey(
            final String secret, final String date,
            final String region, final String service) throws Exception {
        final byte[] kDate    = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        final byte[] kRegion  = hmac(kDate, region);
        final byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    private String extractMessageId(final String responseBody) {
        try {
            return mapper.readTree(responseBody).path("MessageId").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
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
