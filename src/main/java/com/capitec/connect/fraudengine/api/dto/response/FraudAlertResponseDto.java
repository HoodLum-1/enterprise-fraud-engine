package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;

import java.time.Instant;
import java.util.UUID;

public record FraudAlertResponseDto(
    UUID id,
    UUID transactionId,
    String accountId,
    String ruleName,
    int riskScore,
    Severity severity,
    String reason,
    Instant flaggedAt
) {
}
