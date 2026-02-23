package com.abbank.notification.consumer;

import com.abbank.notification.channel.ChannelAdapter;
import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import com.abbank.notification.retry.RetryExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private ChannelAdapter emailAdapter;
    @Mock private ChannelAdapter smsAdapter;
    @Mock private GatewayConfig  config;
    @Mock private RetryExecutor  retry;

    private NotificationDispatcher dispatcher;

    private final CustomerProfile profile = new CustomerProfile(
            1001L, 100001L, "Adaeze", "Okafor",
            "adaeze@test.com", "+2348031001001");

    @BeforeEach
    void setup() {
        lenient().when(config.getForceBothOnSeverity()).thenReturn(List.of("CRITICAL", "HIGH"));

        // Make retry pass through to the actual adapter call
        lenient().when(retry.execute(any(), any())).thenAnswer(inv -> {
            return ((java.util.concurrent.Callable<?>) inv.getArgument(0)).call();
        });

        lenient().when(emailAdapter.channelType()).thenReturn("EMAIL");
        lenient().when(emailAdapter.providerName()).thenReturn("test-email");
        lenient().when(smsAdapter.channelType()).thenReturn("SMS");
        lenient().when(smsAdapter.providerName()).thenReturn("test-sms");

        dispatcher = new NotificationDispatcher(
                List.of(emailAdapter), List.of(smsAdapter), retry, config);
    }

    // ── Channel = EMAIL ───────────────────────────────────────────────────────

    @Test
    void dispatch_sendsEmailOnly_whenChannelIsEmail() {
        final var event = buildEvent(NotificationEvent.Channel.EMAIL, NotificationEvent.Severity.LOW);
        when(emailAdapter.send(event, profile)).thenReturn(success("email"));

        final var results = dispatcher.dispatch(event, profile);

        verify(emailAdapter).send(event, profile);
        verify(smsAdapter, never()).send(any(), any());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isTrue();
    }

    // ── Channel = SMS ─────────────────────────────────────────────────────────

    @Test
    void dispatch_sendsSmsOnly_whenChannelIsSms() {
        final var event = buildEvent(NotificationEvent.Channel.SMS, NotificationEvent.Severity.LOW);
        when(smsAdapter.send(event, profile)).thenReturn(success("sms"));

        final var results = dispatcher.dispatch(event, profile);

        verify(smsAdapter).send(event, profile);
        verify(emailAdapter, never()).send(any(), any());
        assertThat(results).hasSize(1);
    }

    // ── Channel = BOTH ────────────────────────────────────────────────────────

    @Test
    void dispatch_sendsBothChannels_whenChannelIsBoth() {
        final var event = buildEvent(NotificationEvent.Channel.BOTH, NotificationEvent.Severity.MEDIUM);
        when(emailAdapter.send(event, profile)).thenReturn(success("email"));
        when(smsAdapter.send(event, profile)).thenReturn(success("sms"));

        final var results = dispatcher.dispatch(event, profile);

        verify(emailAdapter).send(event, profile);
        verify(smsAdapter).send(event, profile);
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(DeliveryResult::isSuccess);
    }

    // ── Force BOTH on HIGH severity ───────────────────────────────────────────

    @Test
    void dispatch_forcesBoth_whenSeverityIsHigh() {
        // Even though channel = EMAIL only, HIGH severity forces SMS too
        final var event = buildEvent(NotificationEvent.Channel.EMAIL, NotificationEvent.Severity.HIGH);
        when(emailAdapter.send(event, profile)).thenReturn(success("email"));
        when(smsAdapter.send(event, profile)).thenReturn(success("sms"));

        final var results = dispatcher.dispatch(event, profile);

        verify(emailAdapter).send(event, profile);
        verify(smsAdapter).send(event, profile);
        assertThat(results).hasSize(2);
    }

    @Test
    void dispatch_forcesBoth_whenSeverityIsCritical() {
        final var event = buildEvent(NotificationEvent.Channel.SMS, NotificationEvent.Severity.CRITICAL);
        when(emailAdapter.send(event, profile)).thenReturn(success("email"));
        when(smsAdapter.send(event, profile)).thenReturn(success("sms"));

        dispatcher.dispatch(event, profile);

        verify(emailAdapter).send(event, profile);
        verify(smsAdapter).send(event, profile);
    }

    // ── Fallback behaviour ────────────────────────────────────────────────────

    @Test
    void dispatch_fallsBackToSecondAdapter_whenFirstEmailFails() {
        final ChannelAdapter secondEmail = mock(ChannelAdapter.class);
        when(secondEmail.channelType()).thenReturn("EMAIL");
        when(secondEmail.providerName()).thenReturn("backup-email");

        dispatcher = new NotificationDispatcher(
                List.of(emailAdapter, secondEmail), List.of(smsAdapter), retry, config);

        final var event = buildEvent(NotificationEvent.Channel.EMAIL, NotificationEvent.Severity.LOW);
        when(emailAdapter.send(event, profile)).thenReturn(failure("email"));
        when(secondEmail.send(event, profile)).thenReturn(success("backup-email"));

        final var results = dispatcher.dispatch(event, profile);

        verify(emailAdapter).send(event, profile);
        verify(secondEmail).send(event, profile);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(0).getProvider()).isEqualTo("backup-email");
    }

    @Test
    void dispatch_stopsAtFirstSuccess_withMultipleAdapters() {
        final ChannelAdapter secondEmail = mock(ChannelAdapter.class);
        lenient().when(secondEmail.channelType()).thenReturn("EMAIL");
        lenient().when(secondEmail.providerName()).thenReturn("backup-email");

        dispatcher = new NotificationDispatcher(
                List.of(emailAdapter, secondEmail), List.of(smsAdapter), retry, config);

        final var event = buildEvent(NotificationEvent.Channel.EMAIL, NotificationEvent.Severity.LOW);
        when(emailAdapter.send(event, profile)).thenReturn(success("email"));

        dispatcher.dispatch(event, profile);

        // Second adapter must NOT be called once first succeeds
        verify(secondEmail, never()).send(any(), any());
    }

    // ── No adapters configured ────────────────────────────────────────────────

    @Test
    void dispatch_returnsSkipped_whenNoEmailAdapters() {
        dispatcher = new NotificationDispatcher(
                List.of(), List.of(smsAdapter), retry, config);

        final var event = buildEvent(NotificationEvent.Channel.EMAIL, NotificationEvent.Severity.LOW);
        final var results = dispatcher.dispatch(event, profile);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(DeliveryResult.Status.SKIPPED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NotificationEvent buildEvent(
            final NotificationEvent.Channel channel,
            final NotificationEvent.Severity severity) {
        final var e = new NotificationEvent();
        e.setNotificationId("test-" + System.nanoTime());
        e.setNotificationType(NotificationEvent.NotificationType.HIGH_VALUE_ALERT);
        e.setSeverity(severity);
        e.setChannel(channel);
        e.setAccountId(100001L);
        e.setSubject("Test Notification");
        e.setBody("Test body text.");
        return e;
    }

    private DeliveryResult success(final String provider) {
        return DeliveryResult.builder(provider, "EMAIL")
                .success("msg-001", 202)
                .build();
    }

    private DeliveryResult failure(final String provider) {
        return DeliveryResult.builder(provider, "EMAIL")
                .failure("simulated failure", 500)
                .build();
    }
}
