package com.capitec.connect.fraudengine.domain.model;

import java.util.List;
import java.util.UUID;

public record EvaluationSummary(
    UUID transactionId,
    String accountId,
    int totalRiskScore,
    Severity overallSeverity,
    List<RuleEvaluationResult> triggeredRules,
    List<FraudAlert> alerts
) {
    public boolean isFraudulent() {
        return !triggeredRules.isEmpty();
    }
}
