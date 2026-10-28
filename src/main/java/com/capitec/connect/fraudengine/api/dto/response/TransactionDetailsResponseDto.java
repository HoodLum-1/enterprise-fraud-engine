package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionDetailsResponseDto(
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    String category,
    String merchantName,
    String location,
    Instant timestamp,
    boolean isFraudulent,
    Integer totalRiskScore,
    Severity overallSeverity,
    List<FraudAlertResponseDto> alerts
) {}
