package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;

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
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private FraudAlertConsumer fraudAlertConsumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void consumeEvent_persistsNewCaseAndTriggersAccountFreeze_whenCritical() {
        UUID txId = UUID.randomUUID();
        String accountId = "ACC-1001";

        TransactionEvaluatedEvent event = TransactionEvaluatedEvent.builder()
                .transactionId(txId)
                .accountId(accountId)
                .totalRiskScore(90)
                .overallSeverity(Severity.CRITICAL)
                .amount(new BigDecimal("60000.00"))
                .timestamp(Instant.now())
                .build();

        when(fraudCaseRepository.findByTransactionId(txId)).thenReturn(Optional.empty());
        when(fraudCaseMapper.toEntity(any())).thenReturn(new FraudCaseEntity());
        when(fraudCaseRepository.save(any())).thenReturn(new FraudCaseEntity());

        fraudAlertConsumer.consumeTransactionEvaluatedEvent(event, "fraud-alerts-topic");

        verify(fraudCaseRepository).save(any(FraudCaseEntity.class));
        verify(kafkaTemplate).send(eq("account-actions-topic"), eq(accountId), anyString());
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
        verify(kafkaTemplate, never()).send(eq("account-actions-topic"), anyString(), anyString());
    }
}