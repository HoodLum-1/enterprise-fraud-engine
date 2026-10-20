package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@Order(1)
@ConditionalOnProperty(prefix = "fraud.engine.rules.high-amount", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HighAmountRule implements FraudRule {

    private static final String RULE_NAME = "HighAmountRule";

    private final FraudEngineProperties properties;

    public HighAmountRule() {
        this(new FraudEngineProperties());
    }

    @Autowired
    public HighAmountRule(FraudEngineProperties properties) {
        this.properties = properties != null ? properties : new FraudEngineProperties();
    }

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
        FraudEngineProperties.HighAmount config = properties.getRules().getHighAmount();
        if (currentTransaction == null || currentTransaction.amount() == null) {
            return new RuleEvaluationResult(
                RULE_NAME, false, 0, Severity.LOW, "Transaction amount is unavailable"
            );
        }

        String currency = currentTransaction.currency().toUpperCase(java.util.Locale.ROOT);
        BigDecimal threshold = config.getThresholds().get(currency);
        if (threshold == null) {
            throw new UnsupportedCurrencyException(currency);
        }
        int riskScore = config.getRiskScore();
        Severity severity = config.getSeverity();

        if (currentTransaction.amount().compareTo(threshold) > 0) {
            return new RuleEvaluationResult(
                RULE_NAME,
                true,
                riskScore,
                severity,
                "Transaction amount exceeds threshold of " + threshold
            );
        }
        return new RuleEvaluationResult(
            RULE_NAME,
            false,
            0,
            Severity.LOW,
            "Transaction amount within normal threshold"
        );
    }
}
