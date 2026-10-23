package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionVelocityRuleTest {

    private TransactionVelocityRule rule;

    @BeforeEach
    void setUp() {
        rule = new TransactionVelocityRule();
    }

    @Test
    void shouldTriggerWhenFourIncludingCurrentTransactionsOccurWithinTwoMinutes() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-123")
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Johannesburg")
            .timestamp(now)
            .build();

        List<Transaction> history = new ArrayList<>();
        // Three prior transactions plus the current transaction make four.
        for (int i = 1; i <= 3; i++) {
            history.add(FraudTestDataFactory.transaction()
                .accountId("ACC-123")
                .amount(new BigDecimal("50.00"))
                .category("GROCERY")
                .merchantName("Store")
                .location("Johannesburg")
                .timestamp(now.minus(i * 20, ChronoUnit.SECONDS))
                .build());
        }

        RuleEvaluationResult result = rule.evaluate(currentTx, history);

        assertTrue(result.isTriggered());
        assertEquals(50, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
        assertEquals("TransactionVelocityRule", result.ruleName());
    }

    @Test
    void shouldNotTriggerWhenExactlyThreeIncludingCurrentTransactionsOccurWithinTwoMinutes() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-123")
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Johannesburg")
            .timestamp(now)
            .build();

        List<Transaction> history = new ArrayList<>();
        // Two prior transactions plus the current transaction make three.
        for (int i = 1; i <= 2; i++) {
            history.add(FraudTestDataFactory.transaction()
                .accountId("ACC-123")
                .amount(new BigDecimal("50.00"))
                .category("GROCERY")
                .merchantName("Store")
                .location("Johannesburg")
                .timestamp(now.minus(i * 30, ChronoUnit.SECONDS))
                .build());
        }

        RuleEvaluationResult result = rule.evaluate(currentTx, history);

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
    }

    @Test
    void shouldRespectCustomProperties() {
        com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        props.getRules().getVelocity().setThreshold(1);
        props.getRules().getVelocity().setWindow(java.time.Duration.ofMinutes(5));
        props.getRules().getVelocity().setRiskScore(80);
        props.getRules().getVelocity().setSeverity(Severity.CRITICAL);

        TransactionVelocityRule customRule = new TransactionVelocityRule(props);

        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-123")
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Johannesburg")
            .timestamp(now)
            .build();

        List<Transaction> history = List.of(
            FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
                .category("G").merchantName("S").location("J").timestamp(now.minus(3, ChronoUnit.MINUTES)).build(),
            FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
                .category("G").merchantName("S").location("J").timestamp(now.minus(4, ChronoUnit.MINUTES)).build()
        );

        RuleEvaluationResult result = customRule.evaluate(currentTx, history);

        assertTrue(result.isTriggered());
        assertEquals(80, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
    }

    @Test
    void shouldIgnoreTransactionsOlderThanTwoMinutes() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .accountId("ACC-123")
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Johannesburg")
            .timestamp(now)
            .build();

        List<Transaction> history = new ArrayList<>();
        // 2 transactions within 2 minutes, 3 transactions older than 2 minutes
        history.add(FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
            .category("G").merchantName("S").location("J").timestamp(now.minus(30, ChronoUnit.SECONDS)).build());
        history.add(FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
            .category("G").merchantName("S").location("J").timestamp(now.minus(60, ChronoUnit.SECONDS)).build());
        history.add(FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
            .category("G").merchantName("S").location("J").timestamp(now.minus(3, ChronoUnit.MINUTES)).build());
        history.add(FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
            .category("G").merchantName("S").location("J").timestamp(now.minus(5, ChronoUnit.MINUTES)).build());
        history.add(FraudTestDataFactory.transaction().accountId("ACC-123").amount(BigDecimal.TEN)
            .category("G").merchantName("S").location("J").timestamp(now.minus(10, ChronoUnit.MINUTES)).build());

        RuleEvaluationResult result = rule.evaluate(currentTx, history);

        assertFalse(result.isTriggered());
    }


    @Test
    void returnsCleanWhenCurrentTransactionIsNull() {
        assertFalse(rule.evaluate(null, List.of(FraudTestDataFactory.transaction().build())).isTriggered());
    }

    @Test
    void returnsCleanWhenHistoryIsNullOrEmpty() {
        Transaction currentTx = FraudTestDataFactory.transaction().accountId("ACC-1").timestamp(Instant.now()).build();
        assertFalse(rule.evaluate(currentTx, null).isTriggered());
        assertFalse(rule.evaluate(currentTx, List.of()).isTriggered());
    }

    @Test
    void ignoresNullHistoryEntries() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction().timestamp(now).build();
        List<Transaction> history = new ArrayList<>();
        history.add(null);
        history.add(FraudTestDataFactory.transaction().timestamp(now.minus(30, ChronoUnit.SECONDS)).build());
        RuleEvaluationResult result = rule.evaluate(currentTx, history);
        assertFalse(result.isTriggered());
    }

    @Test
    void doesNotTriggerWhenExactlyAtThreshold() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction().timestamp(now).build();
        List<Transaction> history = new ArrayList<>();
        history.add(FraudTestDataFactory.transaction().timestamp(now.minus(10, ChronoUnit.SECONDS)).build());
        history.add(FraudTestDataFactory.transaction().timestamp(now.minus(20, ChronoUnit.SECONDS)).build());
        RuleEvaluationResult result = rule.evaluate(currentTx, history);
        assertFalse(result.isTriggered());
    }
}
