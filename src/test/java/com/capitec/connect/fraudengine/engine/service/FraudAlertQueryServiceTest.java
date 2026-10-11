package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertQueryServiceTest {

    @Mock
    private FraudAlertRepository fraudAlertRepository;

    @Spy
    private FraudAlertMapper fraudAlertMapper = new FraudAlertMapper();

    @InjectMocks
    private FraudAlertQueryService fraudAlertQueryService;

    @Test
    void testGetAlertsByAccountId_ReturnsMappedAlerts() {
        FraudAlertEntity entity = FraudTestDataFactory.fraudAlertEntity()
            .accountId("ACC-123")
            .ruleName("TransactionVelocityRule")
            .riskScore(50)
            .severity(Severity.CRITICAL)
            .reason("High velocity")
            .build();

        when(fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc("ACC-123"))
            .thenReturn(List.of(entity));

        List<FraudAlertResponseDto> result = fraudAlertQueryService.getAlertsByAccountId("ACC-123");

        assertNotNull(result);
        assertEquals(1, result.size());
        FraudAlertResponseDto dto = result.getFirst();
        assertEquals(entity.getId(), dto.id());
        assertEquals(entity.getTransactionId(), dto.transactionId());
        assertEquals("ACC-123", dto.accountId());
        assertEquals("TransactionVelocityRule", dto.ruleName());
        assertEquals(50, dto.riskScore());
        assertEquals(Severity.CRITICAL, dto.severity());
        assertEquals("High velocity", dto.reason());
        assertEquals(entity.getFlaggedAt(), dto.flaggedAt());

        verify(fraudAlertRepository).findByAccountIdOrderByFlaggedAtDesc("ACC-123");
    }

    @Test
    void testGetAlertsByAccountId_WhenNoAlerts_ReturnsEmptyList() {
        when(fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc("ACC-EMPTY"))
            .thenReturn(List.of());

        List<FraudAlertResponseDto> result = fraudAlertQueryService.getAlertsByAccountId("ACC-EMPTY");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(fraudAlertRepository).findByAccountIdOrderByFlaggedAtDesc("ACC-EMPTY");
    }
}
