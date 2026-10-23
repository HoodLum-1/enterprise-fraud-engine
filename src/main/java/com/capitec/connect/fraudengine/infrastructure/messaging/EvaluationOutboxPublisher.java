package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka.
 *
 * <p>The work is split into three steps so that no database transaction is held open while waiting
 * for a Kafka send: a short transaction claims a batch and leases it, the sends happen outside any
 * transaction, and a short transaction records the outcome. A publisher that dies mid-publish
 * leaves the row leased, and another publisher reclaims it once the lease expires.
 *
 * <p>The sends in a claimed batch are started before any of them is awaited, so a batch takes about
 * one send timeout rather than one timeout per row. A poll keeps claiming full batches until the
 * backlog is drained or the configured batch limit is reached, which lets the publisher keep up with
 * a burst instead of draining only one batch per second.
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
    private final FraudMetrics fraudMetrics;

    @Scheduled(fixedDelayString = "${fraud.engine.messaging.outbox-poll-delay-ms:1000}")
    public void publishPendingEvents() {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        int batches = 0;
        int claimedCount;

        do {
            List<EvaluationOutboxEntity> claimed =
                outboxStore.claim(messaging.getBatchSize(), leaseDuration(messaging));
            claimedCount = claimed.size();
            if (!claimed.isEmpty()) {
                publishBatch(claimed, messaging);
            }
            batches++;
        } while (claimedCount == messaging.getBatchSize()
            && batches < messaging.getMaxBatchesPerPoll());
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

    /**
     * Starts every send in the batch first, then waits for the outcomes. Starting the sends up
     * front is what turns the old sequential per-row wait into one concurrent round trip.
     */
    private void publishBatch(List<EvaluationOutboxEntity> rows,
                              FraudEngineProperties.Messaging messaging) {
        List<PendingPublish> pending = new ArrayList<>(rows.size());
        for (EvaluationOutboxEntity row : rows) {
            pending.add(new PendingPublish(row, send(row, messaging)));
        }
        for (PendingPublish publish : pending) {
            await(publish, messaging);
        }
    }

    private CompletableFuture<SendResult<String, Object>> send(EvaluationOutboxEntity row,
                                                               FraudEngineProperties.Messaging messaging) {
        try {
            OutboundEvent event = toOutboundEvent(row);
            return kafkaTemplate.send(event.topic(), row.getEventKey(), event.payload())
                .orTimeout(messaging.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private void await(PendingPublish publish, FraudEngineProperties.Messaging messaging) {
        EvaluationOutboxEntity row = publish.row();
        try {
            publish.send().get(messaging.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            outboxStore.acknowledge(row.getId());
            fraudMetrics.recordOutboxPublish("published");
        } catch (Exception ex) {
            handleFailure(row, rootCause(ex));
        }
    }

    private void handleFailure(EvaluationOutboxEntity row, Throwable ex) {
        FraudEngineProperties.Messaging messaging = properties.getMessaging();
        int attempts = row.getAttempts();

        if (attempts >= messaging.getMaxAttempts()) {
            outboxStore.park(row.getId(), ex.getMessage());
            fraudMetrics.recordOutboxPublish("parked");
            log.error("Outbox event {} parked as FAILED after {} attempts and will not be retried",
                row.getId(), attempts, ex);
            return;
        }

        outboxStore.reschedule(row.getId(), Instant.now().plus(retryDelay(attempts)), ex.getMessage());
        fraudMetrics.recordOutboxPublish("failed");
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

    private Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    private record PendingPublish(EvaluationOutboxEntity row,
                                  CompletableFuture<SendResult<String, Object>> send) {
    }

    private record OutboundEvent(String topic, Object payload) {
    }
}
