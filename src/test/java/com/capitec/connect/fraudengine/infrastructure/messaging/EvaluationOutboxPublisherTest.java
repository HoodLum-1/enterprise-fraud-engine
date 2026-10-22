package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        publisher = new EvaluationOutboxPublisher(outboxStore, kafkaTemplate, objectMapper, properties);
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
