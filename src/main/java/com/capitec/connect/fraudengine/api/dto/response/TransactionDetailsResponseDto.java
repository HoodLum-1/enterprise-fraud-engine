package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One transaction with its evaluation result.
 *
 * <p>{@code totalRiskScore} is omitted rather than zeroed for account holders, so that a caller
 * who may not see the per-alert scores also cannot recover the number from the parent. The overall
 * severity stays: it is the one part of the result the customer can act on, and it is what the
 * freeze decision is explained with.
 */
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
