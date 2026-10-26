package com.capitec.connect.fraudengine.api.dto.response;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * A fraud alert as returned by the API.
 *
 * <p>{@code ruleName} and {@code riskScore} describe how our own detection works: which rule fired
 * and how many points it contributed against the thresholds that decide a freeze. They are the
 * customer's data in the sense that the alert is about their transaction, but they are not useful
 * to them and they hand an attacker the shape of the model. Both are left out of the JSON for
 * account holders and kept for analysts.
 */
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
