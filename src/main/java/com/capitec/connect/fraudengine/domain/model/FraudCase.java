package com.capitec.connect.fraudengine.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FraudCase(
        UUID id,
        UUID transactionId,
        String accountId,
        int totalRiskScore,
        Severity overallSeverity,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public FraudCase {
        Objects.requireNonNull(transactionId, "Transaction ID must not be null");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID must not be null or blank");
        }
        if (totalRiskScore < 0) {
            throw new IllegalArgumentException("Total risk score must not be negative");
        }
        Objects.requireNonNull(overallSeverity, "Overall severity must not be null");
        if (status == null || status.isBlank()) {
            status = "PENDING_REVIEW";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public FraudCase(UUID transactionId, String accountId, int totalRiskScore, Severity overallSeverity) {
        this(UUID.randomUUID(), transactionId, accountId, totalRiskScore, overallSeverity, "PENDING_REVIEW", Instant.now(), Instant.now());
    }
}