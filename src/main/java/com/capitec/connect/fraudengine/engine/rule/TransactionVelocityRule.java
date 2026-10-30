package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Order(2)
@ConditionalOnProperty(prefix = "fraud.engine.rules.velocity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionVelocityRule implements FraudRule {

    private static final String RULE_NAME = "TransactionVelocityRule";
    private static final Logger log = LoggerFactory.getLogger(TransactionVelocityRule.class);

    private final FraudEngineProperties properties;

    public TransactionVelocityRule() {
        this(new FraudEngineProperties());
    }

    @Autowired
    public TransactionVelocityRule(FraudEngineProperties properties) {
        this.properties = properties != null ? properties : new FraudEngineProperties();
    }

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
        if (currentTransaction == null || currentTransaction.timestamp() == null || accountHistory == null || accountHistory.isEmpty()) {
            return new RuleEvaluationResult(RULE_NAME, false, 0, Severity.LOW, "Transaction velocity within acceptable limits");
        }

        FraudEngineProperties.Velocity config = properties.getRules().getVelocity();
        log.info(
                "VELOCITY_DIAGNOSTIC rule: enabled={}, threshold={}, window={}, currentTimestamp={}, historyCount={}",
                config.isEnabled(),
                config.getThreshold(),
                config.getWindow(),
                currentTransaction.timestamp(),
                accountHistory.size()
        );
        Instant currentTimestamp = currentTransaction.timestamp();
        Instant windowStart = currentTimestamp.minus(config.getWindow());

        long previousTransactionCount = accountHistory.stream()
            .filter(tx -> tx != null && tx.timestamp() != null)
            .filter(tx -> !tx.timestamp().isBefore(windowStart) && !tx.timestamp().isAfter(currentTimestamp))
            .count();

        long totalTransactionCount = previousTransactionCount + 1;
        log.info(
                "VELOCITY_DIAGNOSTIC count: previous={}, total={}, threshold={}, triggered={}",
                previousTransactionCount,
                totalTransactionCount,
                config.getThreshold(),
                totalTransactionCount > config.getThreshold()
        );
        if (totalTransactionCount > config.getThreshold()) {
            return new RuleEvaluationResult(
                RULE_NAME,
                true,
                config.getRiskScore(),
                config.getSeverity(),
                "Potential velocity fraud: " + totalTransactionCount + " transactions occurred within the last " + config.getWindow().toMinutes() + " minutes"
            );
        }

        return new RuleEvaluationResult(RULE_NAME, false, 0, Severity.LOW, "Transaction velocity within acceptable limits");
    }
}
