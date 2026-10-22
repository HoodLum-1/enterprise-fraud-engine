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

    @Data
    public static class Evaluation {
        private Duration historyWindow = Duration.ofHours(24);
    }

    @Data
    public static class Lock {
        /** Disabled for the H2-backed unit tests, which have no advisory lock support. */
        private boolean enabled = true;
        private Duration timeout = Duration.ofSeconds(2);
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
