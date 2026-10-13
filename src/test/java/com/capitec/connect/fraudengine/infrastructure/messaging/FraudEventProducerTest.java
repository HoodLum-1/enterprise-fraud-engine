package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FraudEventProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private FraudEventProducer fraudEventProducer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void publishEvaluationEvent_sendsMessageSuccessfully() {
        UUID txId = UUID.randomUUID();
        String accountId = "ACC-2002";
        Transaction transaction = new Transaction(
                txId, accountId, new BigDecimal("1000.00"), "ZAR", 
                "ELECTRONICS", "Store", "Cape Town", Instant.now()
        );
        EvaluationSummary summary = new EvaluationSummary(
                txId, accountId, 10, Severity.LOW, List.of(), List.of()
        );

        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("fraud-alerts-topic"), eq(accountId), any(TransactionEvaluatedEvent.class)))
                .thenReturn(future);

        fraudEventProducer.publishEvaluationEvent(transaction, summary);

        verify(kafkaTemplate).send(eq("fraud-alerts-topic"), eq(accountId), any(TransactionEvaluatedEvent.class));
    }
}