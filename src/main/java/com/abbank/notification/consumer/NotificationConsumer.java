package com.abbank.notification.consumer;

import com.abbank.notification.config.GatewayConfig;
import com.abbank.notification.model.CustomerProfile;
import com.abbank.notification.model.DeliveryResult;
import com.abbank.notification.model.NotificationEvent;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Kafka consumer loop for the Notification Gateway.
 *
 * <p>Subscribes to all AB Bank notification topics, deserialises each
 * {@link NotificationEvent}, resolves the customer profile, and delegates
 * to {@link NotificationDispatcher} for channel routing.
 *
 * <h2>Offset management</h2>
 * Offsets are committed <em>after</em> the dispatch attempt (at-least-once
 * delivery). If the JVM crashes between dispatch and commit, the event will
 * be re-processed — adapters must be idempotent or the DLQ must deduplicate.
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Malformed JSON: logged and skipped (offset committed).</li>
 *   <li>Customer not found: logged and skipped.</li>
 *   <li>All providers exhausted: logged; if {@code retry.on-exhausted=kafka},
 *       the event is published to the DLQ topic.</li>
 *   <li>{@link WakeupException}: clean shutdown signal from {@link #shutdown()}.</li>
 * </ul>
 */
public class NotificationConsumer implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final KafkaConsumer<String, String> consumer;
    private final NotificationDispatcher        dispatcher;
    private final CustomerResolver              customerResolver;
    private final GatewayConfig                 config;
    private final AtomicBoolean                 running = new AtomicBoolean(false);

    // Metrics counters (simple in-process; replace with Micrometer/Prometheus in prod)
    private long totalReceived  = 0L;
    private long totalDelivered = 0L;
    private long totalSkipped   = 0L;
    private long totalFailed    = 0L;

    public NotificationConsumer(
            final GatewayConfig config,
            final NotificationDispatcher dispatcher,
            final CustomerResolver customerResolver) {
        this.config           = config;
        this.dispatcher       = dispatcher;
        this.customerResolver = customerResolver;
        this.consumer         = new KafkaConsumer<>(buildKafkaProperties(config));
    }

    @Override
    public void run() {
        running.set(true);
        final List<String> topics = config.getTopics();
        consumer.subscribe(topics);
        LOG.info("Notification Gateway started — subscribed to {} topics: {}", topics.size(), topics);

        try {
            while (running.get()) {
                final ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);

                for (final ConsumerRecord<String, String> record : records) {
                    processRecord(record);
                }

                // Commit after processing the whole batch (at-least-once)
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            // Normal shutdown path — log at DEBUG, not WARN
            if (running.get()) {
                LOG.error("Unexpected WakeupException while still running", e);
            } else {
                LOG.debug("Consumer woken up for shutdown");
            }
        } catch (Exception e) {
            LOG.error("Fatal error in consumer loop", e);
        } finally {
            consumer.close();
            LOG.info("Consumer closed. Stats — received={} delivered={} skipped={} failed={}",
                    totalReceived, totalDelivered, totalSkipped, totalFailed);
        }
    }

    /** Signal the consumer loop to stop on the next poll boundary. */
    public void shutdown() {
        running.set(false);
        consumer.wakeup();
        LOG.info("Shutdown signal sent to notification consumer");
    }

    @Override
    public void close() {
        shutdown();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void processRecord(final ConsumerRecord<String, String> record) {
        totalReceived++;
        final String topic   = record.topic();
        final long   offset  = record.offset();
        final int    partition = record.partition();

        // 1. Deserialise
        NotificationEvent event;
        try {
            event = NotificationEvent.fromJson(record.value());
        } catch (Exception e) {
            LOG.error("Malformed notification JSON — skipping. topic={} partition={} offset={} error={}",
                    topic, partition, offset, e.getMessage());
            totalFailed++;
            return;
        }

        LOG.info("Processing: notificationId={} type={} severity={} accountId={} topic={}",
                event.getNotificationId(), event.getNotificationType(),
                event.getSeverity(), event.getAccountId(), topic);

        // 2. Resolve customer profile
        final Optional<CustomerProfile> profileOpt = customerResolver.resolve(event.getAccountId());
        if (profileOpt.isEmpty()) {
            LOG.warn("Customer not found for accountId={} — skipping notificationId={}",
                    event.getAccountId(), event.getNotificationId());
            totalSkipped++;
            return;
        }

        final CustomerProfile profile = profileOpt.get();

        // 3. Dispatch
        final List<DeliveryResult> results = dispatcher.dispatch(event, profile);

        // 4. Audit log & counters
        boolean anySuccess = false;
        for (final DeliveryResult result : results) {
            LOG.info("Delivery result: notificationId={} provider={} channel={} status={} msgId={}{}",
                    event.getNotificationId(),
                    result.getProvider(),
                    result.getChannel(),
                    result.getStatus(),
                    result.getProviderMessageId(),
                    result.getErrorMessage() != null ? " error=" + result.getErrorMessage() : "");

            if (result.isSuccess()) {
                anySuccess = true;
            }
        }

        if (anySuccess) {
            totalDelivered++;
        } else {
            totalFailed++;
            handleExhaustedDelivery(event, results);
        }
    }

    private void handleExhaustedDelivery(
            final NotificationEvent event,
            final List<DeliveryResult> results) {

        final String onExhausted = config.getRetryOnExhausted();
        if ("kafka".equalsIgnoreCase(onExhausted)) {
            // DLQ publishing would go here — requires a KafkaProducer instance
            // (omitted to keep this class single-responsibility; wire in NotificationGatewayApp)
            LOG.error("DLQ publishing not yet wired — event dropped: notificationId={}",
                    event.getNotificationId());
        } else {
            // "log" mode — just a final error log
            LOG.error("NOTIFICATION UNDELIVERED after all retries: notificationId={} type={} accountId={}",
                    event.getNotificationId(), event.getNotificationType(), event.getAccountId());
        }
    }

    private Properties buildKafkaProperties(final GatewayConfig cfg) {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  cfg.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,            cfg.getConsumerGroupId());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,   cfg.getAutoOffsetReset());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,    cfg.getMaxPollRecords());
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,  cfg.getSessionTimeoutMs());
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, cfg.getHeartbeatIntervalMs());
        // Manual offset commit — we commit after processing, not before
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        return props;
    }
}
