package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FraudEventProducerTest {

    private FraudEventProducer fraudEventProducer;

    @BeforeEach
    void setUp() {
        fraudEventProducer = new FraudEventProducer();
    }

    @Test
    void buildEvent_includesEvaluationFields() {
        UUID txId = UUID.randomUUID();
        String accountId = "ACC-2002";
        Transaction transaction = new Transaction(
                txId, accountId, new BigDecimal("1000.00"), "ZAR", 
                "ELECTRONICS", "Store", "Cape Town", Instant.now()
        );
        EvaluationSummary summary = new EvaluationSummary(
                txId, accountId, 10, Severity.LOW, List.of(), List.of()
        );

        TransactionEvaluatedEvent event = fraudEventProducer.buildEvent(transaction, summary);
        assertEquals(txId, event.getTransactionId());
        assertEquals(accountId, event.getAccountId());
        assertEquals(transaction.category(), event.getCategory());
        assertFalse(event.isFraudulent());
    }
}