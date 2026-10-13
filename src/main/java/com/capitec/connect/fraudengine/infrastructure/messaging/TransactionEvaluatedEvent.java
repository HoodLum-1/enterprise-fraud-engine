package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.Severity;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event published to Kafka when a transaction is evaluated for fraud.
 * Contains the complete evaluation summary for downstream consumers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEvaluatedEvent {

    @JsonProperty("eventId")
    private UUID eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    @JsonProperty("transactionId")
    private UUID transactionId;

    @JsonProperty("accountId")
    private String accountId;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("category")
    private String category;

    @JsonProperty("merchantName")
    private String merchantName;

    @JsonProperty("location")
    private String location;

    @JsonProperty("totalRiskScore")
    private int totalRiskScore;

    @JsonProperty("overallSeverity")
    private Severity overallSeverity;

    @JsonProperty("isFraudulent")
    private boolean isFraudulent;

    @JsonProperty("triggeredRules")
    private List<RuleEvaluationInfo> triggeredRules;

    @JsonProperty("alertsGenerated")
    private int alertsGenerated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleEvaluationInfo {
        @JsonProperty("ruleName")
        private String ruleName;

        @JsonProperty("triggered")
        private boolean triggered;

        @JsonProperty("riskScore")
        private int riskScore;

        @JsonProperty("severity")
        private Severity severity;

        @JsonProperty("reason")
        private String reason;
    }
}