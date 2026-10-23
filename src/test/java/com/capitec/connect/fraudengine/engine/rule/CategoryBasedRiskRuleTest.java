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


    @Test
    void doesNotTriggerForANullTransaction() {
        assertFalse(rule.evaluate(null, List.of()).isTriggered());
    }

    @Test
    void doesNotTriggerWithUnconfiguredCategorySet() {
        var props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        props.getRules().getCategoryRisk().setCategories(java.util.Set.of());
        var ruleWithProps = new CategoryBasedRiskRule(props);
        assertFalse(ruleWithProps.evaluate(FraudTestDataFactory.transaction().category("GAMBLING").build(), List.of()).isTriggered());
    }

    @Test
    void appliesPerCategoryOverrides() {
        var props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        var override = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties.CategoryRiskOverride();
        override.setRiskScore(77);
        override.setSeverity(Severity.CRITICAL);
        props.getRules().getCategoryRisk().setOverrides(java.util.Map.of("CRYPTO", override));
        var ruleWithProps = new CategoryBasedRiskRule(props);

        var result = ruleWithProps.evaluate(
            FraudTestDataFactory.transaction().category("crypto").build(), List.of());

        assertTrue(result.isTriggered());
        assertEquals(77, result.riskScore());
        assertEquals(Severity.CRITICAL, result.severity());
    }

    @Test
    void toleratesANullEntryInTheConfiguredCategories() {
        var props = new com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties();
        props.getRules().getCategoryRisk().setCategories(
            new java.util.HashSet<>(java.util.Arrays.asList(null, "CRYPTO")));
        var ruleWithProps = new CategoryBasedRiskRule(props);

        assertTrue(ruleWithProps.evaluate(
            FraudTestDataFactory.transaction().category("CRYPTO").build(), List.of()).isTriggered());
    }
}
