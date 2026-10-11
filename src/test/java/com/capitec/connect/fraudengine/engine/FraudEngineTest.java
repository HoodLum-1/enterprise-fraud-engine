package com.capitec.connect.fraudengine.engine;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.rule.HighAmountRule;
import com.capitec.connect.fraudengine.engine.rule.LocationMismatchRule;
import com.capitec.connect.fraudengine.engine.rule.TransactionVelocityRule;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FraudEngineTest {

    private FraudEngine fraudEngine;

    @BeforeEach
    void setUp() {
        fraudEngine = new FraudEngine(List.of(
            new HighAmountRule(),
            new TransactionVelocityRule(),
            new LocationMismatchRule()
        ));
    }

    @Test
    void shouldEvaluateMultipleTriggeredRulesAndCalculateCumulativeScore() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-100")
            .amount(new BigDecimal("60000.00"))
            .category("JEWELRY")
            .merchantName("Luxury Gems")
            .location("Durban")
            .timestamp(now)
            .build();
        UUID txId = currentTx.id();

        List<Transaction> history = new ArrayList<>();
        // 4 transactions in last minute -> triggers TransactionVelocityRule (50, CRITICAL)
        for (int i = 1; i <= 4; i++) {
            history.add(FraudTestDataFactory.transaction()
                .accountId("ACC-100")
                .amount(new BigDecimal("100.00"))
                .category("RETAIL")
                .merchantName("Store")
                .location("Cape Town")
                .timestamp(now.minus(i * 15, ChronoUnit.SECONDS))
                .build());
        }

        EvaluationSummary summary = fraudEngine.evaluate(currentTx, history);

        assertNotNull(summary);
        assertEquals(txId, summary.transactionId());
        assertEquals("ACC-100", summary.accountId());
        assertTrue(summary.isFraudulent());
        assertEquals(3, summary.triggeredRules().size());
        assertEquals(3, summary.alerts().size());
        assertEquals(40 + 50 + 60, summary.totalRiskScore());
        assertEquals(Severity.CRITICAL, summary.overallSeverity());
    }

    @Test
    void shouldEvaluateCleanTransactionWithZeroRiskScore() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-100")
            .amount(new BigDecimal("500.00"))
            .category("GROCERY")
            .merchantName("Market")
            .location("Cape Town")
            .timestamp(now)
            .build();

        List<Transaction> history = List.of(
            FraudTestDataFactory.transaction()
                .accountId("ACC-100")
                .amount(new BigDecimal("300.00"))
                .category("GROCERY")
                .merchantName("Market")
                .location("Cape Town")
                .timestamp(now.minus(20, ChronoUnit.MINUTES))
                .build()
        );

        EvaluationSummary summary = fraudEngine.evaluate(currentTx, history);

        assertNotNull(summary);
        assertFalse(summary.isFraudulent());
        assertEquals(0, summary.triggeredRules().size());
        assertEquals(0, summary.alerts().size());
        assertEquals(0, summary.totalRiskScore());
        assertEquals(Severity.LOW, summary.overallSeverity());
    }
}
