package com.abbank.notification.consumer;

import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.retry.RetryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryExecutorTest {

    @Mock private GatewayConfig config;

    private RetryExecutor executor;

    @BeforeEach
    void setup() {
        when(config.getRetryMaxAttempts()).thenReturn(3);
        when(config.getRetryInitialDelayMs()).thenReturn(10L);  // fast for tests
        when(config.getRetryBackoffFactor()).thenReturn(2.0);
        when(config.getRetryMaxDelayMs()).thenReturn(100L);
        executor = new RetryExecutor(config);
    }

    @Test
    void execute_returnsImmediately_onFirstSuccess() throws Exception {
        final var result = executor.execute(
                () -> success("sendgrid"), "sendgrid/EMAIL test-id");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void execute_retriesOnFailure_andSucceedsOnSecondAttempt() throws Exception {
        final var counter = new AtomicInteger(0);
        final var result  = executor.execute(() -> {
            if (counter.incrementAndGet() == 1) return failure("sendgrid");
            return success("sendgrid");
        }, "sendgrid/EMAIL test-id");

        assertThat(result.isSuccess()).isTrue();
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void execute_exhaustsAllAttemptsAndReturnsLastFailure() throws Exception {
        final var counter = new AtomicInteger(0);
        final var result  = executor.execute(() -> {
            counter.incrementAndGet();
            return failure("sendgrid");
        }, "sendgrid/EMAIL test-id");

        assertThat(result.isSuccess()).isFalse();
        assertThat(counter.get()).isEqualTo(3); // maxAttempts = 3
    }

    @Test
    void execute_doesNotRetry_onSkipped() throws Exception {
        final var counter = new AtomicInteger(0);
        final var result  = executor.execute(() -> {
            counter.incrementAndGet();
            return skipped("sendgrid");
        }, "sendgrid/EMAIL test-id");

        assertThat(result.getStatus()).isEqualTo(DeliveryResult.Status.SKIPPED);
        assertThat(counter.get()).isEqualTo(1); // only one attempt for SKIPPED
    }

    @Test
    void execute_handlesException_andRetries() throws Exception {
        final var counter = new AtomicInteger(0);
        final var result  = executor.execute(() -> {
            if (counter.incrementAndGet() < 3) {
                throw new RuntimeException("network timeout");
            }
            return success("sendgrid");
        }, "sendgrid/EMAIL test-id");

        assertThat(result.isSuccess()).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeliveryResult success(final String provider) {
        return DeliveryResult.builder(provider, "EMAIL").success("msg-001", 202).build();
    }

    private DeliveryResult failure(final String provider) {
        return DeliveryResult.builder(provider, "EMAIL").failure("server error", 500).build();
    }

    private DeliveryResult skipped(final String provider) {
        return DeliveryResult.builder(provider, "EMAIL").skipped("no email address").build();
    }
}
