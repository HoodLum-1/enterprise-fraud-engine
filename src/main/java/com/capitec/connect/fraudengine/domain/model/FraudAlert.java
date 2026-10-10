package com.capitec.connect.fraudengine.domain.model;

import java.time.Instant;
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
) {}
