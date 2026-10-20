package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    int totalRiskScore,
    Severity overallSeverity,
    List<FraudAlertResponseDto> alerts
) {}
