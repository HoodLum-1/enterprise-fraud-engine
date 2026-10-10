package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;

import java.util.List;
import java.util.UUID;

public record EvaluationSummaryResponseDto(
    UUID transactionId,
    String accountId,
    int totalRiskScore,
    Severity overallSeverity,
    boolean isFraudulent,
    List<FraudAlertResponseDto> alerts
) {
}
