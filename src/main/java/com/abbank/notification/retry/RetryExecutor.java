package com.abbank.notification.retry;

import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.model.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Executes a notification dispatch with exponential back-off and jitter.
 *
 * <p>A {@link DeliveryResult} with {@link DeliveryResult.Status#FAILURE} from
 * a channel adapter is treated as a retryable error. A result with
 * {@link DeliveryResult.Status#SKIPPED} (e.g. no email on record) is
 * <em>not</em> retried — it is a permanent condition.
 *
 * <h2>Back-off formula</h2>
 * <pre>
 *   delay(attempt) = min(initialDelay × factor^attempt + jitter, maxDelay)
 *   jitter         = random(0, initialDelay)
 * </pre>
 */
public class RetryExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RetryExecutor.class);
    private static final Random RNG = new Random();

    private final int    maxAttempts;
    private final long   initialDelayMs;
    private final double backoffFactor;
    private final long   maxDelayMs;

    public RetryExecutor(final GatewayConfig config) {
        this.maxAttempts    = config.getRetryMaxAttempts();
        this.initialDelayMs = config.getRetryInitialDelayMs();
        this.backoffFactor  = config.getRetryBackoffFactor();
        this.maxDelayMs     = config.getRetryMaxDelayMs();
    }

    /**
     * Execute {@code operation} with automatic retry on {@link DeliveryResult.Status#FAILURE}.
     *
     * @param operation  the dispatch call to attempt
     * @param description human-readable description for log messages
     * @return the first successful {@link DeliveryResult}, or the last failure result
     *         after all attempts are exhausted
     */
    public DeliveryResult execute(
            final Callable<DeliveryResult> operation,
            final String description) {

        DeliveryResult lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                lastResult = operation.call();

                if (lastResult.isSuccess()) {
                    if (attempt > 1) {
                        LOG.info("Retry succeeded: {} on attempt {}/{}", description, attempt, maxAttempts);
                    }
                    return lastResult;
                }

                // SKIPPED is permanent — do not retry
                if (lastResult.getStatus() == DeliveryResult.Status.SKIPPED) {
                    return lastResult;
                }

                // FAILURE — log and back off unless this is the last attempt
                LOG.warn("Delivery failed (attempt {}/{}): {} — {}",
                        attempt, maxAttempts, description, lastResult.getErrorMessage());

                if (attempt < maxAttempts) {
                    sleep(backoffDelay(attempt));
                }
            } catch (Exception e) {
                LOG.error("Unexpected exception during delivery (attempt {}/{}): {} — {}",
                        attempt, maxAttempts, description, e.getMessage());
                lastResult = DeliveryResult.builder(
                                extractProvider(description), extractChannel(description))
                        .failure("Exception: " + e.getMessage(), 0)
                        .build();

                if (attempt < maxAttempts) {
                    sleep(backoffDelay(attempt));
                }
            }
        }

        LOG.error("All {} retry attempts exhausted for: {}", maxAttempts, description);
        return lastResult;
    }

    private long backoffDelay(final int attempt) {
        final long base   = (long) (initialDelayMs * Math.pow(backoffFactor, attempt - 1));
        final long jitter = (long) (RNG.nextDouble() * initialDelayMs);
        return Math.min(base + jitter, maxDelayMs);
    }

    private void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractProvider(final String description) {
        // Description format: "provider/channel notificationId=..."
        final int slash = description.indexOf('/');
        return slash > 0 ? description.substring(0, slash) : "unknown";
    }

    private String extractChannel(final String description) {
        final int slash = description.indexOf('/');
        final int space = description.indexOf(' ', slash);
        if (slash > 0 && space > slash) {
            return description.substring(slash + 1, space);
        }
        return "unknown";
    }
}
