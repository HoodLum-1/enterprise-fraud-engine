package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighAmountRuleTest {

    private HighAmountRule rule;

    @BeforeEach
    void setUp() {
        rule = new HighAmountRule();
    }

    @Test
    void shouldTriggerWhenAmountExceedsFiftyThousand() {
        Transaction tx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("50000.01"))
            .build();

        RuleEvaluationResult result = rule.evaluate(tx, List.of());

        assertTrue(result.isTriggered());
        assertEquals(40, result.riskScore());
        assertEquals(Severity.HIGH, result.severity());
        assertEquals("HighAmountRule", result.ruleName());
        assertNotNull(result.reason());
    }

    @Test
    void shouldNotTriggerWhenAmountIsFiftyThousandOrLess() {
        Transaction tx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("50000.00"))
            .build();

        RuleEvaluationResult result = rule.evaluate(tx, List.of());

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
        assertEquals(Severity.LOW, result.severity());
    }

    @Test
    void shouldRespectCustomProperties() {
        com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        props.getRules().getHighAmount().setThreshold(new BigDecimal("10000.00"));
        props.getRules().getHighAmount().setRiskScore(75);
        props.getRules().getHighAmount().setSeverity(Severity.CRITICAL);

        HighAmountRule customRule = new HighAmountRule(props);

        Transaction tx = FraudTestDataFactory.transaction()
            .amount(new BigDecimal("15000.00"))
            .build();

        RuleEvaluationResult result = customRule.evaluate(tx, List.of());

        assertTrue(result.isTriggered());
        assertEquals(75, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
    }

    @Test
    void shouldHandleNullTransaction() {
        RuleEvaluationResult result = rule.evaluate(null, List.of());

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
    }
}
