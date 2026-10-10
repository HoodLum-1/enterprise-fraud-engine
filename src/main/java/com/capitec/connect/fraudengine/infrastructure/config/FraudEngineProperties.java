package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.domain.model.Severity;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "fraud.engine.rules")
public class FraudEngineProperties {

    private HighAmount highAmount = new HighAmount();
    private Velocity velocity = new Velocity();
    private LocationMismatch locationMismatch = new LocationMismatch();

    @Data
    public static class HighAmount {
        private boolean enabled = true;
        private BigDecimal threshold = new BigDecimal("50000.00");
        private int riskScore = 40;
        private Severity severity = Severity.HIGH;
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
}
