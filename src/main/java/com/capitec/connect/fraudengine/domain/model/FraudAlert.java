package com.capitec.connect.fraudengine.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FraudAlert(
    UUID id,
    UUID transactionId,
    String accountId,
    String ruleName,
    int riskScore,
    Severity severity,
    String reason,
    Instant flaggedAt
) {
    public FraudAlert {
        Objects.requireNonNull(id, "Fraud alert ID must not be null");
        Objects.requireNonNull(transactionId, "Transaction ID must not be null");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID must not be null or blank");
        }
        if (ruleName == null || ruleName.isBlank()) {
            throw new IllegalArgumentException("Rule name must not be null or blank");
        }
        if (riskScore < 0) {
            throw new IllegalArgumentException("Risk score must not be negative");
        }
        Objects.requireNonNull(severity, "Severity must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason must not be null or blank");
        }
        Objects.requireNonNull(flaggedAt, "Flagged at timestamp must not be null");
    }
}
