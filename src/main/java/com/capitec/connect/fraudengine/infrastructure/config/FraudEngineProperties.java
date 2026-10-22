package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.domain.model.Severity;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "fraud.engine")
public class FraudEngineProperties {

    @NestedConfigurationProperty
    private Evaluation evaluation = new Evaluation();

    @NestedConfigurationProperty
    private Rules rules = new Rules();

    @NestedConfigurationProperty
    private FreezePolicy freezePolicy = new FreezePolicy();

    @NestedConfigurationProperty
    private Lock lock = new Lock();

    @NestedConfigurationProperty
    private Messaging messaging = new Messaging();

    @Data
    public static class Evaluation {
        private Duration historyWindow = Duration.ofHours(24);
        /** How far a transaction timestamp may be ahead of the service clock. */
        private Duration maxFutureSkew = Duration.ofMinutes(5);
    }

    @Data
    public static class Lock {
        /** Disabled for the H2-backed unit tests, which have no advisory lock support. */
        private boolean enabled = true;
        private Duration timeout = Duration.ofSeconds(2);
    }

    @Data
    public static class Messaging {
        private String transactionTopic = "transactions-topic";
        private String evaluationTopic = "fraud-alerts-topic";
        private String accountActionsTopic = "account-actions-topic";
        private long outboxPollDelayMs = 1000L;
        private boolean outboxPublisherEnabled = true;

        /** How many rows a publisher claims per poll. */
        private int batchSize = 20;

        /** How long a single Kafka send may take before it is treated as failed. */
        private long sendTimeoutMs = 5000L;

        /** Extra time added to the lease on top of the worst case for a whole batch. */
        private long leaseMarginMs = 30000L;

        /** How many times a row may be claimed before it is parked as FAILED. */
        private int maxAttempts = 10;

        /** How long delivered rows are kept before they are pruned. */
        private Duration retention = Duration.ofDays(7);

        /** How many rows are deleted per retention sweep. */
        private int pruneBatchSize = 500;

        /** How often the retention sweep runs. */
        private long outboxRetentionSweepMs = 3600000L;
    }

    @Data
    public static class Rules {
        private HighAmount highAmount = new HighAmount();
        private Velocity velocity = new Velocity();
        private LocationMismatch locationMismatch = new LocationMismatch();
        private CategoryRisk categoryRisk = new CategoryRisk();
    }

    @Data
    public static class HighAmount {
        private boolean enabled = true;
        private Map<String, BigDecimal> thresholds = Map.of("ZAR", new BigDecimal("50000.00"));
        private int riskScore = 40;
        private Severity severity = Severity.HIGH;
    }

    @Data
    public static class CategoryRisk {
        private boolean enabled = true;
        private java.util.Set<String> categories = java.util.Set.of("GAMBLING", "CRYPTO");
        private int riskScore = 30;
        private Severity severity = Severity.MEDIUM;
    }

    @Data
    public static class Velocity {
        private boolean enabled = true;
        private int threshold = 3;
        private Duration window = Duration.ofMinutes(2);
        private int riskScore = 50;
        private Severity severity = Severity.CRITICAL;
    }

    @Data
    public static class LocationMismatch {
        private boolean enabled = true;
        private Duration timeGapThreshold = Duration.ofMinutes(10);
        private int riskScore = 60;
        private Severity severity = Severity.CRITICAL;
    }

    @Data
    public static class FreezePolicy {
        private boolean enabled = true;
        private int minimumRiskScore = 80;
        private Severity minimumSeverity = Severity.CRITICAL;
    }
}
