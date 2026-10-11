package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FraudAlertMapperTest {

    private FraudAlertMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new FraudAlertMapper();
    }

    @Test
    void shouldMapEntityToDomain() {
        Instant now = Instant.now();

        FraudAlertEntity entity = FraudTestDataFactory.fraudAlertEntity()
            .accountId("ACC-100")
            .ruleName("HighAmountRule")
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("Exceeds 50000")
            .flaggedAt(now)
            .build();

        FraudAlert domain = mapper.toDomain(entity);

        assertNotNull(domain);
        assertEquals(entity.getId(), domain.id());
        assertEquals(entity.getTransactionId(), domain.transactionId());
        assertEquals("ACC-100", domain.accountId());
        assertEquals("HighAmountRule", domain.ruleName());
        assertEquals(40, domain.riskScore());
        assertEquals(Severity.HIGH, domain.severity());
        assertEquals("Exceeds 50000", domain.reason());
        assertEquals(now, domain.flaggedAt());
    }

    @Test
    void shouldMapDomainToEntity() {
        Instant now = Instant.now();

        FraudAlert domain = FraudTestDataFactory.fraudAlert()
            .accountId("ACC-100")
            .ruleName("HighAmountRule")
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("Exceeds 50000")
            .flaggedAt(now)
            .build();

        FraudAlertEntity entity = mapper.toEntity(domain);

        assertNotNull(entity);
        assertEquals(domain.id(), entity.getId());
        assertEquals(domain.transactionId(), entity.getTransactionId());
        assertEquals("ACC-100", entity.getAccountId());
        assertEquals("HighAmountRule", entity.getRuleName());
        assertEquals(40, entity.getRiskScore());
        assertEquals(Severity.HIGH, entity.getSeverity());
        assertEquals("Exceeds 50000", entity.getReason());
        assertEquals(now, entity.getFlaggedAt());
    }

    @Test
    void shouldReturnNullWhenMappingNull() {
        assertNull(mapper.toDomain(null));
        assertNull(mapper.toEntity(null));
    }
}
