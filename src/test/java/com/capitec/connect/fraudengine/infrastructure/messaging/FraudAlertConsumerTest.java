package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.FreezeAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudAlertConsumerTest {

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private FraudCaseMapper fraudCaseMapper;

    @Mock
    private FreezeAuditRepository freezeAuditRepository;

    @Mock
    private EvaluationOutboxRepository outboxRepository;

    @Mock
    private FraudEngineProperties engineProperties;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FraudAlertConsumer fraudAlertConsumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void consumeEvent_persistsFlaggedCaseAndAuditsFreeze_whenCritical() throws Exception {
        UUID txId = UUID.randomUUID();
        String accountId = "ACC-1001";

        TransactionEvaluatedEvent event = TransactionEvaluatedEvent.builder()
                .transactionId(txId)
                .accountId(accountId)
                .totalRiskScore(90)
                .overallSeverity(Severity.CRITICAL)
                .isFraudulent(true)
                .alertsGenerated(1)
                .amount(new BigDecimal("60000.00"))
                .timestamp(Instant.now())
                .build();

        when(fraudCaseRepository.findByTransactionId(txId)).thenReturn(Optional.empty());
        when(fraudCaseMapper.toEntity(any())).thenReturn(new FraudCaseEntity());
        when(fraudCaseRepository.save(any())).thenReturn(new FraudCaseEntity());
        when(engineProperties.getFreezePolicy()).thenReturn(new FraudEngineProperties.FreezePolicy());
        when(freezeAuditRepository.existsByTransactionId(txId)).thenReturn(false);
        when(objectMapper.writeValueAsString(any(AccountActionEvent.class))).thenReturn("{}");

        fraudAlertConsumer.consumeTransactionEvaluatedEvent(event, "fraud-alerts-topic");

        verify(fraudCaseRepository).save(any(FraudCaseEntity.class));
        verify(freezeAuditRepository).save(any());
        verify(outboxRepository).save(argThat(outbox -> "ACCOUNT_FREEZE".equals(outbox.getEventType())));
    }

    @Test
    void consumeEvent_cleanEvaluationDoesNotCreateCaseOrNotification() {
        TransactionEvaluatedEvent event = TransactionEvaluatedEvent.builder()
            .transactionId(UUID.randomUUID())
            .accountId("ACC-1001")
            .totalRiskScore(0)
            .overallSeverity(Severity.LOW)
            .isFraudulent(false)
            .alertsGenerated(0)
            .build();

        fraudAlertConsumer.consumeTransactionEvaluatedEvent(event, "fraud-alerts-topic");

        verifyNoInteractions(fraudCaseRepository, fraudCaseMapper, freezeAuditRepository, outboxRepository);
    }

    @Test
    void consumeEvent_skipsPersist_whenDuplicateTransaction() {
        UUID txId = UUID.randomUUID();
        TransactionEvaluatedEvent event = TransactionEvaluatedEvent.builder()
                .transactionId(txId)
                .accountId("ACC-1001")
                .totalRiskScore(20)
                .overallSeverity(Severity.LOW)
                .build();

        when(fraudCaseRepository.findByTransactionId(txId)).thenReturn(Optional.of(new FraudCaseEntity()));

        fraudAlertConsumer.consumeTransactionEvaluatedEvent(event, "fraud-alerts-topic");

        verify(fraudCaseRepository, never()).save(any());
        verifyNoInteractions(freezeAuditRepository, outboxRepository);
    }
}