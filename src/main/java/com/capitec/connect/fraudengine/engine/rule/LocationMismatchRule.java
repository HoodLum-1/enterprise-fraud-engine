package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
@Order(3)
@ConditionalOnProperty(prefix = "fraud.engine.rules.location-mismatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocationMismatchRule implements FraudRule {

    private static final String RULE_NAME = "LocationMismatchRule";

    private final FraudEngineProperties properties;

    public LocationMismatchRule() {
        this(new FraudEngineProperties());
    }

    @Autowired
    public LocationMismatchRule(FraudEngineProperties properties) {
        this.properties = properties != null ? properties : new FraudEngineProperties();
    }

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
        if (currentTransaction == null || currentTransaction.location() == null || currentTransaction.timestamp() == null
            || accountHistory == null || accountHistory.isEmpty()) {
            return new RuleEvaluationResult(RULE_NAME, false, 0, Severity.LOW, "No location mismatch detected");
        }

        FraudEngineProperties.LocationMismatch config = properties.getRules().getLocationMismatch();

        Transaction mostRecent = accountHistory.stream()
            .filter(tx -> tx != null && tx.timestamp() != null)
            .filter(tx -> !tx.timestamp().isAfter(currentTransaction.timestamp()))
            .max(Comparator.comparing(Transaction::timestamp))
            .orElse(null);

        if (mostRecent != null && mostRecent.location() != null) {
            boolean locationDiffers = !currentTransaction.location().trim().equalsIgnoreCase(mostRecent.location().trim());
            Duration gap = Duration.between(mostRecent.timestamp(), currentTransaction.timestamp()).abs();
            if (locationDiffers && gap.compareTo(config.getTimeGapThreshold()) < 0) {
                return new RuleEvaluationResult(
                    RULE_NAME,
                    true,
                    config.getRiskScore(),
                    config.getSeverity(),
                    "Location mismatch detected: transaction at location '" + currentTransaction.location()
                        + "' occurred within " + config.getTimeGapThreshold().toMinutes() + " minutes of previous transaction at location '"
                        + mostRecent.location() + "'"
                );
            }
        }

        return new RuleEvaluationResult(RULE_NAME, false, 0, Severity.LOW, "No location mismatch detected");
    }
}
