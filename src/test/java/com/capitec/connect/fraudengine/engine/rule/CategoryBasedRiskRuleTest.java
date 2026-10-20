package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CategoryBasedRiskRuleTest {

    private final CategoryBasedRiskRule rule = new CategoryBasedRiskRule();

    @Test
    void triggersForConfiguredCategoryIgnoringCase() {
        var result = rule.evaluate(FraudTestDataFactory.transaction().category(" crypto ").build(), List.of());

        assertTrue(result.isTriggered());
        assertEquals(30, result.riskScore());
        assertEquals(Severity.MEDIUM, result.severity());
    }

    @Test
    void doesNotTriggerForOtherCategory() {
        var result = rule.evaluate(FraudTestDataFactory.transaction().category("GROCERIES").build(), List.of());

        assertFalse(result.isTriggered());
        assertEquals(0, result.riskScore());
    }
}
