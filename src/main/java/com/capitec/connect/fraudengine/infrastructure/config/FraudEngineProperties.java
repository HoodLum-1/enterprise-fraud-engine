package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.domain.model.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "fraud.engine", ignoreUnknownFields = false)
public class FraudEngineProperties {

    @Valid
    @NestedConfigurationProperty
    private Evaluation evaluation = new Evaluation();

    @Valid
    @NestedConfigurationProperty
    private Rules rules = new Rules();

    @Valid
    @NestedConfigurationProperty
    private FreezePolicy freezePolicy = new FreezePolicy();

    @Valid
    @NestedConfigurationProperty
    private Lock lock = new Lock();

    @Valid
    @NestedConfigurationProperty
    private Messaging messaging = new Messaging();

    @Data
    public static class Evaluation {
        @MinDuration(seconds = 1)
        private Duration historyWindow = Duration.ofHours(24);
        /** How far a transaction timestamp may be ahead of the service clock. */
        @MinDuration(seconds = 0)
        private Duration maxFutureSkew = Duration.ofMinutes(5);
        /** How far a transaction timestamp may be in the past. */
        @MinDuration(seconds = 0)
        private Duration maxPastSkew = Duration.ofDays(7);
    }

    @Data
    public static class Lock {
        /** Disabled for the H2-backed unit tests, which have no advisory lock support. */
        private boolean enabled = true;
        @MinDuration(seconds = 1)
        private Duration timeout = Duration.ofSeconds(2);
    }

    @Data
    public static class Messaging {
        private String transactionTopic = "transactions-topic";
        private String evaluationTopic = "fraud-alerts-topic";
        private String accountActionsTopic = "account-actions-topic";

        @Positive
        private long outboxPollDelayMs = 1000L;
        private boolean outboxPublisherEnabled = true;

        /** How many rows a publisher claims per poll. */
        @Positive
        private int batchSize = 20;

        /**
         * How many full batches one poll may drain before it yields to the scheduler. This keeps a
         * very large backlog from monopolising the scheduler thread while still letting a normal
         * burst drain in one poll.
         */
        @Positive
        private int maxBatchesPerPoll = 100;

        /** How long a single Kafka send may take before it is treated as failed. */
        @Positive
        private long sendTimeoutMs = 5000L;

        /** Extra time added to the lease on top of the worst case for a whole batch. */
        @PositiveOrZero
        private long leaseMarginMs = 30000L;

        /** How many times a row may be claimed before it is parked as FAILED. */
        @Positive
        private int maxAttempts = 10;

        /** How long delivered rows are kept before they are pruned. */
        @MinDuration(seconds = 1)
        private Duration retention = Duration.ofDays(7);

        /** How many rows are deleted per retention sweep. */
        @Positive
        private int pruneBatchSize = 500;

        /** How often the retention sweep runs. */
        @Positive
        private long outboxRetentionSweepMs = 3600000L;

        /** How often the outbox backlog gauges are refreshed. */
        @Positive
        private long outboxMetricsRefreshMs = 10000L;

        /** Whether the dead letter queue depth is collected from the broker. */
        private boolean dlqMetricsEnabled = true;

        /** How often the dead letter queue gauges are refreshed. */
        @Positive
        private long dlqMetricsRefreshMs = 30000L;
    }

    @Data
    public static class Rules {
        @Valid
        private HighAmount highAmount = new HighAmount();
        @Valid
        private Velocity velocity = new Velocity();
        @Valid
        private LocationMismatch locationMismatch = new LocationMismatch();
        @Valid
        private CategoryRisk categoryRisk = new CategoryRisk();
    }

    @Data
    public static class HighAmount {
        private boolean enabled = true;
        private Map<String, BigDecimal> thresholds = Map.of(
            "ZAR", new BigDecimal("50000.00"),
            "USD", new BigDecimal("2500.00"));
        @PositiveOrZero
        private int riskScore = 40;
        private Severity severity = Severity.HIGH;
    }

    @Data
    public static class CategoryRisk {
        private boolean enabled = true;
        private java.util.Set<String> categories = java.util.Set.of("GAMBLING", "CRYPTO");
        @PositiveOrZero
        private int riskScore = 30;
        private Severity severity = Severity.MEDIUM;
        /** Per-category overrides. Key is case-insensitive. */
        @Valid
        private java.util.Map<String, CategoryRiskOverride> overrides = java.util.Map.of();
    }

    @Data
    public static class CategoryRiskOverride {
        @PositiveOrZero
        private Integer riskScore;
        private Severity severity;
    }

    @Data
    public static class Velocity {
        private boolean enabled = true;
        @Positive
        private int threshold = 3;
        @MinDuration(seconds = 1)
        private Duration window = Duration.ofMinutes(2);
        @PositiveOrZero
        private int riskScore = 50;
        private Severity severity = Severity.CRITICAL;
    }

    @Data
    public static class LocationMismatch {
        private boolean enabled = true;
        @MinDuration(seconds = 1)
        private Duration timeGapThreshold = Duration.ofMinutes(10);
        @PositiveOrZero
        private int riskScore = 60;
        private Severity severity = Severity.CRITICAL;
    }

    @Data
    public static class FreezePolicy {
        private boolean enabled = true;
        @PositiveOrZero
        private int minimumRiskScore = 80;
        private Severity minimumSeverity = Severity.CRITICAL;
    }
}
