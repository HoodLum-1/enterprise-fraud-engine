package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.CaseStatus;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FraudCaseServiceTest {

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private FraudCaseMapper fraudCaseMapper;

    private FraudCaseService fraudCaseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fraudCaseService = new FraudCaseService(fraudCaseRepository, fraudCaseMapper);
    }

    @Test
    void createFromEvaluation_persistsNewFraudCase() {
        UUID txId = UUID.randomUUID();
        FraudCaseEntity mockEntity = new FraudCaseEntity();
        mockEntity.setStatus(CaseStatus.PENDING_REVIEW);

        when(fraudCaseMapper.toEntity(any(FraudCase.class))).thenReturn(mockEntity);
        when(fraudCaseRepository.save(any(FraudCaseEntity.class))).thenReturn(mockEntity);
        when(fraudCaseMapper.toDomain(mockEntity)).thenReturn(
                new FraudCase(UUID.randomUUID(), txId, "ACC-1001", 80, Severity.CRITICAL,
                    CaseStatus.PENDING_REVIEW, null, null, null, 0)
        );

        FraudCase result = fraudCaseService.createFromEvaluation(txId, "ACC-1001", 80, Severity.CRITICAL);

        assertNotNull(result);
        assertEquals(CaseStatus.PENDING_REVIEW, result.status());
        verify(fraudCaseRepository).save(any(FraudCaseEntity.class));
    }

    @Test
    void updateStatus_existingCase_updatesStatus() {
        UUID txId = UUID.randomUUID();
        FraudCaseEntity existing = new FraudCaseEntity();
        existing.setTransactionId(txId);
        existing.setStatus(CaseStatus.PENDING_REVIEW);

        when(fraudCaseRepository.findByTransactionId(txId)).thenReturn(Optional.of(existing));
        when(fraudCaseRepository.save(any())).thenReturn(existing);
        when(fraudCaseMapper.toDomain(existing)).thenReturn(
                new FraudCase(UUID.randomUUID(), txId, "ACC-1001", 80, Severity.CRITICAL,
                    CaseStatus.CONFIRMED_FRAUD, null, null, "analyst", 1)
        );

        FraudCase result = fraudCaseService.updateStatus(txId, CaseStatus.CONFIRMED_FRAUD, "analyst");

        assertNotNull(result);
        assertEquals(CaseStatus.CONFIRMED_FRAUD, result.status());
        verify(fraudCaseRepository).save(existing);
    }

    @Test
    void updateStatus_unknownTransaction_throws() {
        when(fraudCaseRepository.findByTransactionId(any())).thenReturn(Optional.empty());

        assertThrows(com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException.class,
                () -> fraudCaseService.updateStatus(UUID.randomUUID(), CaseStatus.CONFIRMED_FRAUD, "analyst"));
    }

    @Test
    void findByTransactionId_existing_returnsCase() {
        UUID txId = UUID.randomUUID();
        FraudCaseEntity entity = new FraudCaseEntity();
        entity.setTransactionId(txId);
        entity.setAccountId("ACC-1001");
        entity.setTotalRiskScore(40);
        entity.setOverallSeverity(Severity.HIGH);
        entity.setStatus(CaseStatus.PENDING_REVIEW);

        when(fraudCaseRepository.findByTransactionId(txId)).thenReturn(Optional.of(entity));
        when(fraudCaseMapper.toDomain(entity)).thenReturn(
                new FraudCase(UUID.randomUUID(), txId, "ACC-1001", 40, Severity.HIGH,
                    CaseStatus.PENDING_REVIEW, null, null, null, 0)
        );

        FraudCase result = fraudCaseService.findByTransactionId(txId);

        assertNotNull(result);
        assertEquals("ACC-1001", result.accountId());
        assertEquals(40, result.totalRiskScore());
        assertEquals(Severity.HIGH, result.overallSeverity());
    }

    @Test
    void findByTransactionId_unknown_returnsNull() {
        when(fraudCaseRepository.findByTransactionId(any())).thenReturn(Optional.empty());

        assertNull(fraudCaseService.findByTransactionId(UUID.randomUUID()));
    }
}