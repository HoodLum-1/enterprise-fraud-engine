package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudAlertResponseDto(
    UUID id,
    UUID transactionId,
    String accountId,
    String ruleName,
    Integer riskScore,
    Severity severity,
    String reason,
    Instant flaggedAt
) {
}
