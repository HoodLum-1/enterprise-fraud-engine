package com.capitec.connect.fraudengine.util;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FraudTestDataFactory {

    private static final String ACCOUNT_ID = "ACC-1001";
    private static final String CURRENCY = "ZAR";
    private static final String CATEGORY = "ELECTRONICS";
    private static final String MERCHANT_NAME = "Apple Store";
    private static final String LOCATION = "Johannesburg";

    private FraudTestDataFactory() {
    }

    public static TransactionRequestBuilder transactionRequest() {
        return new TransactionRequestBuilder();
    }

    public static TransactionBuilder transaction() {
        return new TransactionBuilder();
    }

    public static TransactionEntity.TransactionEntityBuilder transactionEntity() {
        Instant now = Instant.now();
        return TransactionEntity.builder()
            .id(UUID.randomUUID())
            .accountId(ACCOUNT_ID)
            .amount(new BigDecimal("50000.00"))
            .currency(CURRENCY)
            .category(CATEGORY)
            .merchantName(MERCHANT_NAME)
            .location(LOCATION)
            .timestamp(now)
            .createdAt(now);
    }

    public static FraudAlertBuilder fraudAlert() {
        return new FraudAlertBuilder();
    }

    public static FraudAlertEntity.FraudAlertEntityBuilder fraudAlertEntity() {
        Instant now = Instant.now();
        return FraudAlertEntity.builder()
            .id(UUID.randomUUID())
            .transactionId(UUID.randomUUID())
            .accountId(ACCOUNT_ID)
            .ruleName("HighAmountRule")
            .riskScore(10)
            .severity(Severity.LOW)
            .reason("Test fraud alert")
            .flaggedAt(now);
    }

    public static EvaluationSummary evaluationSummary(
        UUID transactionId,
        String accountId,
        int totalRiskScore,
        Severity overallSeverity,
        List<RuleEvaluationResult> triggeredRules,
        List<FraudAlert> alerts
    ) {
        return new EvaluationSummary(
            transactionId,
            accountId,
            totalRiskScore,
            overallSeverity,
            triggeredRules,
            alerts
        );
    }

    public static EvaluationSummary evaluationSummary() {
        return evaluationSummary(UUID.randomUUID(), ACCOUNT_ID, 0, Severity.LOW, List.of(), List.of());
    }

    public static final class TransactionRequestBuilder {
        private UUID id = UUID.randomUUID();
        private String accountId = ACCOUNT_ID;
        private BigDecimal amount = new BigDecimal("50000.00");
        private String currency = CURRENCY;
        private String category = CATEGORY;
        private String merchantName = MERCHANT_NAME;
        private String location = LOCATION;
        private Instant timestamp = Instant.now();

        private TransactionRequestBuilder() {
        }

        public TransactionRequestBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public TransactionRequestBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public TransactionRequestBuilder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public TransactionRequestBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public TransactionRequestBuilder category(String category) {
            this.category = category;
            return this;
        }

        public TransactionRequestBuilder merchantName(String merchantName) {
            this.merchantName = merchantName;
            return this;
        }

        public TransactionRequestBuilder location(String location) {
            this.location = location;
            return this;
        }

        public TransactionRequestBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TransactionRequestDto build() {
            return new TransactionRequestDto(
                id, accountId, amount, currency, category, merchantName, location, timestamp
            );
        }
    }

    public static final class TransactionBuilder {
        private UUID id = UUID.randomUUID();
        private String accountId = ACCOUNT_ID;
        private BigDecimal amount = new BigDecimal("50000.00");
        private String currency = CURRENCY;
        private String category = CATEGORY;
        private String merchantName = MERCHANT_NAME;
        private String location = LOCATION;
        private Instant timestamp = Instant.now();

        private TransactionBuilder() {
        }

        public TransactionBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public TransactionBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public TransactionBuilder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public TransactionBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public TransactionBuilder category(String category) {
            this.category = category;
            return this;
        }

        public TransactionBuilder merchantName(String merchantName) {
            this.merchantName = merchantName;
            return this;
        }

        public TransactionBuilder location(String location) {
            this.location = location;
            return this;
        }

        public TransactionBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Transaction build() {
            return new Transaction(id, accountId, amount, currency, category, merchantName, location, timestamp);
        }
    }

    public static final class FraudAlertBuilder {
        private UUID id = UUID.randomUUID();
        private UUID transactionId = UUID.randomUUID();
        private String accountId = ACCOUNT_ID;
        private String ruleName = "HighAmountRule";
        private int riskScore = 10;
        private Severity severity = Severity.LOW;
        private String reason = "Test fraud alert";
        private Instant flaggedAt = Instant.now();

        private FraudAlertBuilder() {
        }

        public FraudAlertBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public FraudAlertBuilder transactionId(UUID transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public FraudAlertBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public FraudAlertBuilder ruleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        public FraudAlertBuilder riskScore(int riskScore) {
            this.riskScore = riskScore;
            return this;
        }

        public FraudAlertBuilder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        public FraudAlertBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public FraudAlertBuilder flaggedAt(Instant flaggedAt) {
            this.flaggedAt = flaggedAt;
            return this;
        }

        public FraudAlert build() {
            return new FraudAlert(id, transactionId, accountId, ruleName, riskScore, severity, reason, flaggedAt);
        }
    }
}
