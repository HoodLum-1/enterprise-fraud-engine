package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationOutboxPublisherTest {

    @Mock
    private OutboxStore outboxStore;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private FraudEngineProperties properties;
    private EvaluationOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        properties = new FraudEngineProperties();
        publisher = new EvaluationOutboxPublisher(outboxStore, kafkaTemplate, objectMapper, properties,
            new FraudMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void aDeliveredEventIsAcknowledged() throws Exception {
        EvaluationOutboxEntity row = pendingRow(1);
        when(outboxStore.claim(anyInt(), any(Duration.class))).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("fraud-alerts-topic"), eq("ACC-1001"), any());
        verify(outboxStore).acknowledge(row.getId());
        verify(outboxStore, never()).reschedule(any(), any(), any());
        verify(outboxStore, never()).park(any(), any());
    }

    @Test
    void aFailedEventIsRescheduledWithABackoff() throws Exception {
        EvaluationOutboxEntity row = pendingRow(1);
        when(outboxStore.claim(anyInt(), any(Duration.class))).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publishPendingEvents();

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(outboxStore).reschedule(eq(row.getId()), nextAttempt.capture(), any());
        assertTrue(nextAttempt.getValue().isAfter(Instant.now()), "the retry should be scheduled in the future");
        verify(outboxStore, never()).acknowledge(any());
        verify(outboxStore, never()).park(any(), any());
    }

    @Test
    void anEventThatExhaustsItsRetriesIsParkedAsFailed() throws Exception {
        EvaluationOutboxEntity row = pendingRow(properties.getMessaging().getMaxAttempts());
        when(outboxStore.claim(anyInt(), any(Duration.class))).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publishPendingEvents();

        verify(outboxStore).park(eq(row.getId()), any());
        verify(outboxStore, never()).reschedule(any(), any(), any());
        verify(outboxStore, never()).acknowledge(any());
    }

    @Test
    void allSendsInABatchAreStartedBeforeAnyIsAwaited() throws Exception {
        EvaluationOutboxEntity firstRow = pendingRow(1);
        EvaluationOutboxEntity secondRow = pendingRow(1);
        CompletableFuture<SendResult<String, Object>> firstSend = new CompletableFuture<>();
        CompletableFuture<SendResult<String, Object>> secondSend = new CompletableFuture<>();

        when(outboxStore.claim(anyInt(), any(Duration.class)))
            .thenReturn(List.of(firstRow, secondRow))
            .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), any(), any())).thenReturn(firstSend, secondSend);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var invocation = executor.submit(publisher::publishPendingEvents);

            verify(kafkaTemplate, timeout(1000).times(2)).send(anyString(), any(), any());
            firstSend.complete(null);
            secondSend.complete(null);
            invocation.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(outboxStore).acknowledge(firstRow.getId());
        verify(outboxStore).acknowledge(secondRow.getId());
    }

    @Test
    void aFullBatchCausesAnotherClaimBeforeThePollReturns() throws Exception {
        int batchSize = properties.getMessaging().getBatchSize();
        List<EvaluationOutboxEntity> rows = java.util.stream.IntStream.range(0, batchSize)
            .mapToObj(index -> {
                try {
                    return pendingRow(1);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            })
            .toList();
        when(outboxStore.claim(anyInt(), any(Duration.class)))
            .thenReturn(rows)
            .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(outboxStore, times(2)).claim(anyInt(), any(Duration.class));
        verify(kafkaTemplate, times(batchSize)).send(anyString(), any(), any());
        verify(outboxStore, times(batchSize)).acknowledge(any());
    }

    @Test
    void thePerPollBatchLimitStopsAnEndlessBacklogFromMonopolisingTheScheduler() throws Exception {
        int batchSize = properties.getMessaging().getBatchSize();
        properties.getMessaging().setMaxBatchesPerPoll(2);
        List<EvaluationOutboxEntity> rows = java.util.stream.IntStream.range(0, batchSize)
            .mapToObj(index -> {
                try {
                    return pendingRow(1);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            })
            .toList();
        when(outboxStore.claim(anyInt(), any(Duration.class))).thenReturn(rows);
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(outboxStore, times(2)).claim(anyInt(), any(Duration.class));
        verify(outboxStore, times(batchSize * 2)).acknowledge(any());
    }

    @Test
    void oneFailedSendDoesNotPreventTheRestOfTheBatchFromBeingAcknowledged() throws Exception {
        EvaluationOutboxEntity failed = pendingRow(1);
        EvaluationOutboxEntity delivered = pendingRow(1);
        when(outboxStore.claim(anyInt(), any(Duration.class)))
            .thenReturn(List.of(failed, delivered))
            .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(outboxStore).reschedule(eq(failed.getId()), any(), any());
        verify(outboxStore).acknowledge(delivered.getId());
        verify(outboxStore, never()).acknowledge(failed.getId());
    }

    @Test
    void theLeaseOutlivesTheSendTimeoutPlusMargin() {
        ArgumentCaptor<Duration> lease = ArgumentCaptor.forClass(Duration.class);
        when(outboxStore.claim(anyInt(), lease.capture())).thenReturn(List.of());

        publisher.publishPendingEvents();

        long expected = (long) properties.getMessaging().getBatchSize()
            * properties.getMessaging().getSendTimeoutMs()
            + properties.getMessaging().getLeaseMarginMs();
        assertEquals(expected, lease.getValue().toMillis());
        assertTrue(lease.getValue().toMillis() > properties.getMessaging().getSendTimeoutMs());
    }

    @Test
    void retentionPruningDeletesInBoundedBatches() {
        when(outboxStore.prunePublishedBefore(any(Instant.class), anyInt())).thenReturn(7);

        publisher.prunePublishedEvents();

        verify(outboxStore).prunePublishedBefore(any(Instant.class),
            eq(properties.getMessaging().getPruneBatchSize()));
    }

    private EvaluationOutboxEntity pendingRow(int attempts) throws Exception {
        TransactionEvaluatedEvent event = TransactionEvaluatedEvent.builder()
            .eventId(UUID.randomUUID())
            .eventType("TRANSACTION_EVALUATED")
            .timestamp(Instant.now())
            .transactionId(UUID.randomUUID())
            .accountId("ACC-1001")
            .totalRiskScore(40)
            .overallSeverity(Severity.HIGH)
            .isFraudulent(true)
            .triggeredRules(List.of())
            .alertsGenerated(1)
            .build();

        return EvaluationOutboxEntity.builder()
            .id(UUID.randomUUID())
            .eventKey("ACC-1001")
            .eventType("TRANSACTION_EVALUATED")
            .payload(objectMapper.writeValueAsString(event))
            .createdAt(Instant.now())
            .attempts(attempts)
            .build();
    }
}
