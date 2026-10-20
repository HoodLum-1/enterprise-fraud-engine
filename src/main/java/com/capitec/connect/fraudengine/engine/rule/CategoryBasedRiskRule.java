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
        boolean highRisk = config.getCategories().stream()
            .filter(category -> category != null)
            .map(category -> category.toUpperCase(Locale.ROOT))
            .anyMatch(category -> category.equals(currentTransaction.category().trim().toUpperCase(Locale.ROOT)));

        if (!highRisk) {
            return notTriggered();
        }
        return new RuleEvaluationResult(
            RULE_NAME,
            true,
            config.getRiskScore(),
            config.getSeverity(),
            "Transaction category '" + currentTransaction.category() + "' is configured as high risk"
        );
    }

    private RuleEvaluationResult notTriggered() {
        return new RuleEvaluationResult(
            RULE_NAME, false, 0, Severity.LOW, "Transaction category is not configured as high risk"
        );
    }
}
