package com.capitec.connect.fraudengine.engine.service;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    void returnsPagedMappedAlertsAndFilters() {
        FraudAlertEntity entity = FraudTestDataFactory.fraudAlertEntity()
            .accountId("ACC-123")
            .ruleName("TransactionVelocityRule")
            .riskScore(50)
            .severity(Severity.CRITICAL)
            .reason("High velocity")
            .build();
        PageRequest pageable = PageRequest.of(0, 20);
        Page<FraudAlertEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
        when(fraudAlertRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        var result = fraudAlertQueryService.getAlertsByAccountId(
            "ACC-123", Severity.CRITICAL, "TransactionVelocityRule", null, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("ACC-123", result.getContent().getFirst().accountId());
        assertEquals(Severity.CRITICAL, result.getContent().getFirst().severity());
        verify(fraudAlertRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void returnsEmptyPageWhenAccountHasNoAlerts() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(fraudAlertRepository.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(Page.empty(pageable));

        var result = fraudAlertQueryService.getAlertsByAccountId(
            "ACC-EMPTY", null, null, null, null, pageable);

        assertTrue(result.isEmpty());
        verify(fraudAlertRepository).findAll(any(Specification.class), eq(pageable));
    }
}
