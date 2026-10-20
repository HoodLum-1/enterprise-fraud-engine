package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.CaseStatus;
import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.Severity;

import java.time.Instant;
import java.util.UUID;

public record FraudCaseResponseDto(
    UUID id,
    UUID transactionId,
    String accountId,
    int totalRiskScore,
    Severity overallSeverity,
    CaseStatus status,
    Instant createdAt,
    Instant updatedAt,
    String updatedBy,
    long version
) {
    public static FraudCaseResponseDto from(FraudCase fraudCase) {
        return new FraudCaseResponseDto(
            fraudCase.id(),
            fraudCase.transactionId(),
            fraudCase.accountId(),
            fraudCase.totalRiskScore(),
            fraudCase.overallSeverity(),
            fraudCase.status(),
            fraudCase.createdAt(),
            fraudCase.updatedAt(),
            fraudCase.updatedBy(),
            fraudCase.version()
        );
    }
}
