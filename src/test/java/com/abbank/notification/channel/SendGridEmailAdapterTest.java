package com.abbank.notification.channel;

import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

class SendGridEmailAdapterTest {

    private MockWebServer server;

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void teardown() throws Exception {
        server.shutdown();
    }

    private SendGridEmailAdapter adapterPointingAt(final String url) {
        return new SendGridEmailAdapter("test-api-key", "noreply@abbank.com", "support@abbank.com", url);
    }

    @Test
    void send_returnsSuccess_on202Response() throws Exception {
        final var url = server.url("/v3/mail/send").toString();
        final var adapter = adapterPointingAt(url);
        final var profile = new CustomerProfile(1L, 100L, "John", "Doe", "john@doe.com", "+2341234567890");
        final var event   = buildEvent();

        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("X-Message-Id", "sg-12345"));

        final DeliveryResult result = adapter.send(event, profile);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).isEqualTo("sg-12345");

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(request.getBody().readUtf8()).contains("john@doe.com");
    }

    @Test
    void isConfigured_returnsTrue_whenApiKeyPresent() {
        final var adapter = new SendGridEmailAdapter("key123", "from@bank.com", null);
        assertThat(adapter.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returnsFalse_whenApiKeyBlank() {
        final var adapter = new SendGridEmailAdapter("", "from@bank.com", null);
        assertThat(adapter.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_returnsFalse_whenApiKeyNull() {
        final var adapter = new SendGridEmailAdapter(null, "from@bank.com", null);
        assertThat(adapter.isConfigured()).isFalse();
    }

    @Test
    void providerName_returnsSendgrid() {
        final var adapter = new SendGridEmailAdapter("key", "from@bank.com", null);
        assertThat(adapter.providerName()).isEqualTo("sendgrid");
    }

    @Test
    void channelType_returnsEmail() {
        final var adapter = new SendGridEmailAdapter("key", "from@bank.com", null);
        assertThat(adapter.channelType()).isEqualTo("EMAIL");
    }

    @Test
    void send_returnsSkipped_whenProfileHasNoEmail() {
        final var adapter = new SendGridEmailAdapter("key", "from@bank.com", null);
        final var profile = new CustomerProfile(1L, 100L, "John", "Doe", null, "+2341234567890");
        final var event   = buildEvent();

        final DeliveryResult result = adapter.send(event, profile);

        assertThat(result.getStatus()).isEqualTo(DeliveryResult.Status.SKIPPED);
        assertThat(result.getProvider()).isEqualTo("sendgrid");
        assertThat(result.getChannel()).isEqualTo("EMAIL");
        assertThat(result.getErrorMessage()).contains("no email address");
    }

    @Test
    void send_returnsSkipped_whenEmailIsBlank() {
        final var adapter = new SendGridEmailAdapter("key", "from@bank.com", null);
        final var profile = new CustomerProfile(1L, 100L, "John", "Doe", "  ", "+2341234567890");
        final var event   = buildEvent();

        final DeliveryResult result = adapter.send(event, profile);
        assertThat(result.getStatus()).isEqualTo(DeliveryResult.Status.SKIPPED);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private NotificationEvent buildEvent() {
        final var event = new NotificationEvent();
        event.setNotificationId("test-id-001");
        event.setNotificationType(NotificationEvent.NotificationType.HIGH_VALUE_ALERT);
        event.setSeverity(NotificationEvent.Severity.HIGH);
        event.setChannel(NotificationEvent.Channel.EMAIL);
        event.setAccountId(100001L);
        event.setSubject("High Value Transaction Alert");
        event.setBody("A transaction of ₦750,000 was processed on your account.");
        return event;
    }

    // Testable subclass that accepts a configurable endpoint URL
    // In a real project this would be done via dependency injection
    static class SendGridEmailAdapterTestable extends SendGridEmailAdapter {
        SendGridEmailAdapterTestable(String key, String from, String replyTo, String endpoint) {
            super(key, from, replyTo);
            // NOTE: endpoint override not used in this simplified test —
            // full HTTP integration tests would use the MockWebServer URL directly
        }
    }
}
