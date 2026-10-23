package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@Order(4)
@ConditionalOnProperty(prefix = "fraud.engine.rules.category-risk", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CategoryBasedRiskRule implements FraudRule {

    private static final String RULE_NAME = "CategoryBasedRiskRule";

    private final FraudEngineProperties properties;

    public CategoryBasedRiskRule() {
        this(new FraudEngineProperties());
    }

    @Autowired
    public CategoryBasedRiskRule(FraudEngineProperties properties) {
        this.properties = properties != null ? properties : new FraudEngineProperties();
    }

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public int getOrder() {
        return 4;
    }

    @Override
    public RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
        if (currentTransaction == null || currentTransaction.category() == null) {
            return notTriggered();
        }

        FraudEngineProperties.CategoryRisk config = properties.getRules().getCategoryRisk();
        String categoryKey = currentTransaction.category().trim().toUpperCase(Locale.ROOT);
        boolean highRisk = config.getCategories().stream()
            .filter(category -> category != null)
            .map(category -> category.toUpperCase(Locale.ROOT))
            .anyMatch(category -> category.equals(categoryKey));

        if (!highRisk) {
            return notTriggered();
        }

        int riskScore = config.getRiskScore();
        Severity severity = config.getSeverity();
        if (config.getOverrides() != null) {
            FraudEngineProperties.CategoryRiskOverride override = config.getOverrides().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().trim().toUpperCase(Locale.ROOT).equals(categoryKey))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            if (override != null) {
                if (override.getRiskScore() != null) {
                    riskScore = override.getRiskScore();
                }
                if (override.getSeverity() != null) {
                    severity = override.getSeverity();
                }
            }
        }

        return new RuleEvaluationResult(
            RULE_NAME,
            true,
            riskScore,
            severity,
            "Transaction category '" + currentTransaction.category() + "' is configured as high risk"
        );
    }

    private RuleEvaluationResult notTriggered() {
        return new RuleEvaluationResult(
            RULE_NAME, false, 0, Severity.LOW, "Transaction category is not configured as high risk"
        );
    }
}
