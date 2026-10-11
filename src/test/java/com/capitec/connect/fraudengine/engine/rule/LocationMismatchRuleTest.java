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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocationMismatchRuleTest {

    private LocationMismatchRule rule;

    @BeforeEach
    void setUp() {
        rule = new LocationMismatchRule();
    }

    @Test
    void shouldTriggerWhenLocationDiffersAndGapIsUnderTenMinutes() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Durban")
            .timestamp(now)
            .build();

        Transaction pastTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("200.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .timestamp(now.minus(5, ChronoUnit.MINUTES))
            .build();

        RuleEvaluationResult result = rule.evaluate(currentTx, List.of(pastTx));

        assertTrue(result.isTriggered());
        assertEquals(60, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
        assertEquals("LocationMismatchRule", result.ruleName());
    }

    @Test
    void shouldNotTriggerWhenLocationIsSameWithinTenMinutes() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .timestamp(now)
            .build();

        Transaction pastTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("200.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .timestamp(now.minus(5, ChronoUnit.MINUTES))
            .build();

        RuleEvaluationResult result = rule.evaluate(currentTx, List.of(pastTx));

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
    }

    @Test
    void shouldRespectCustomProperties() {
        com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        props.getRules().getLocationMismatch().setTimeGapThreshold(java.time.Duration.ofMinutes(30));
        props.getRules().getLocationMismatch().setRiskScore(90);
        props.getRules().getLocationMismatch().setSeverity(Severity.CRITICAL);

        LocationMismatchRule customRule = new LocationMismatchRule(props);

        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Durban")
            .timestamp(now)
            .build();

        Transaction pastTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("200.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .timestamp(now.minus(20, ChronoUnit.MINUTES))
            .build();

        RuleEvaluationResult result = customRule.evaluate(currentTx, List.of(pastTx));

        assertTrue(result.isTriggered());
        assertEquals(90, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
    }

    @Test
    void shouldNotTriggerWhenLocationDiffersButGapIsTenMinutesOrMore() {
        Instant now = Instant.now();
        Transaction currentTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Durban")
            .timestamp(now)
            .build();

        Transaction pastTx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("200.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .timestamp(now.minus(15, ChronoUnit.MINUTES))
            .build();

        RuleEvaluationResult result = rule.evaluate(currentTx, List.of(pastTx));

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
    }
}
