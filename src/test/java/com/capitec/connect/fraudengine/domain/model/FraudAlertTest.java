package com.capitec.connect.fraudengine.domain.model;

import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FraudAlertTest {

    @Test
    void shouldCreateValidFraudAlert() {
        Instant now = Instant.now();

        FraudAlert alert = FraudTestDataFactory.fraudAlert()
            .accountId("ACC-1")
            .ruleName("HighAmountRule")
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("Exceeded threshold")
            .flaggedAt(now)
            .build();

        assertNotNull(alert.id());
        assertNotNull(alert.transactionId());
        assertEquals("ACC-1", alert.accountId());
        assertEquals("HighAmountRule", alert.ruleName());
        assertEquals(40, alert.riskScore());
        assertEquals(Severity.HIGH, alert.severity());
        assertEquals("Exceeded threshold", alert.reason());
        assertEquals(now, alert.flaggedAt());
    }

    @Test
    void shouldThrowExceptionWhenIdOrTransactionIdIsNull() {
        assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.fraudAlert().id(null).build()
        );

        assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.fraudAlert().transactionId(null).build()
        );
    }

    @Test
    void shouldThrowExceptionWhenAccountIdOrRuleNameOrReasonIsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.fraudAlert().accountId("  ").build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.fraudAlert().ruleName("  ").build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.fraudAlert().reason("  ").build()
        );
    }

    @Test
    void shouldThrowExceptionWhenRiskScoreIsNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.fraudAlert().riskScore(-1).build()
        );
    }

    @Test
    void shouldThrowExceptionWhenSeverityOrFlaggedAtIsNull() {
        assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.fraudAlert().severity(null).build()
        );

        assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.fraudAlert().flaggedAt(null).build()
        );
    }
}
