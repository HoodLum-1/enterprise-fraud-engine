package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka.
 *
 * <p>The work is split into three steps so that no database transaction is held open while waiting
 * for a Kafka send: a short transaction claims a batch and leases it, the sends happen outside any
 * transaction, and a short transaction records the outcome. A publisher that dies mid-publish
 * leaves the row leased, and another publisher reclaims it once the lease expires.
 *
 * <p>Delivery is at-least-once and unordered. Rows are keyed by account, so a topic's partitions
 * keep a per-account order in the common case, but a redelivery or a second publisher may
 * interleave events. Consumers must deduplicate on the event id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "fraud.engine.messaging", name = "outbox-publisher-enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationOutboxPublisher {

    private final OutboxStore outboxStore;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final FraudEngineProperties properties;

    @Scheduled(fixedDelayString = "${fraud.engine.messaging.outbox-poll-delay-ms:1000}")
    public void publishPendingEvents() {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        List<EvaluationOutboxEntity> claimed =
            outboxStore.claim(messaging.getBatchSize(), leaseDuration(messaging));

        for (EvaluationOutboxEntity row : claimed) {
            publish(row);
        }
    }

    /**
     * Removes delivered rows once they are older than the retention period. The delete is bounded
     * so it never holds a long transaction.
     */
    @Scheduled(fixedDelayString = "${fraud.engine.messaging.outbox-retention-sweep-ms:3600000}")
    public void prunePublishedEvents() {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        int removed = outboxStore.prunePublishedBefore(
            Instant.now().minus(messaging.getRetention()), messaging.getPruneBatchSize());
        if (removed > 0) {
            log.info("Pruned {} delivered outbox rows older than {}", removed, messaging.getRetention());
        }
    }

    private void publish(EvaluationOutboxEntity row) {
        try {
            OutboundEvent event = toOutboundEvent(row);
            kafkaTemplate.send(event.topic(), row.getEventKey(), event.payload())
                .get(properties.getMessaging().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            outboxStore.acknowledge(row.getId());
        } catch (Exception ex) {
            handleFailure(row, ex);
        }
    }

    private void handleFailure(EvaluationOutboxEntity row, Exception ex) {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        int attempts = row.getAttempts();

        if (attempts >= messaging.getMaxAttempts()) {
            outboxStore.park(row.getId(), ex.getMessage());
            log.error("Outbox event {} parked as FAILED after {} attempts and will not be retried",
                row.getId(), attempts, ex);
            return;
        }

        outboxStore.reschedule(row.getId(), Instant.now().plus(retryDelay(attempts)), ex.getMessage());
        log.warn("Outbox event {} failed on attempt {}, retrying in {}", row.getId(), attempts,
            retryDelay(attempts), ex);
    }

    /**
     * The lease has to outlive the worst case for the whole claimed batch, otherwise a slow but
     * healthy publish would be reclaimed by another publisher while it is still in flight.
     */
    private Duration leaseDuration(FraudEngineProperties.Messaging messaging) {
        long publishBudget = (long) messaging.getBatchSize() * messaging.getSendTimeoutMs();
        return Duration.ofMillis(publishBudget + messaging.getLeaseMarginMs());
    }

    private Duration retryDelay(int attempts) {
        return Duration.ofSeconds(Math.min(300L, 1L << Math.min(attempts, 8)));
    }

    private OutboundEvent toOutboundEvent(EvaluationOutboxEntity row) throws Exception {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        return switch (row.getEventType()) {
            case "TRANSACTION_EVALUATED" -> new OutboundEvent(messaging.getEvaluationTopic(),
                objectMapper.readValue(row.getPayload(), TransactionEvaluatedEvent.class));
            case "ACCOUNT_FREEZE" -> new OutboundEvent(messaging.getAccountActionsTopic(),
                objectMapper.readValue(row.getPayload(), AccountActionEvent.class));
            default -> throw new IllegalArgumentException("Unsupported outbox event type: " + row.getEventType());
        };
    }

    private record OutboundEvent(String topic, Object payload) {
    }
}
