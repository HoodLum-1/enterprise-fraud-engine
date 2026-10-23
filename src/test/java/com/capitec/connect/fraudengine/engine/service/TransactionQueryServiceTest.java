package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FraudAlertRepository fraudAlertRepository;

    private TransactionQueryService service;
    private TransactionEntity transaction;

    @BeforeEach
    void setUp() {
        service = new TransactionQueryService(transactionRepository, fraudAlertRepository,
            new FraudAlertMapper());
        transaction = TransactionEntity.builder()
            .id(UUID.randomUUID())
            .accountId("ACC-QUERY-1")
            .amount(new BigDecimal("125.50"))
            .currency("ZAR")
            .category("GROCERIES")
            .merchantName("Market")
            .location("Cape Town")
            .timestamp(Instant.parse("2026-01-15T08:30:00Z"))
            .build();
    }

    @Test
    void returnsTransactionWithAlertsAndASummedRiskScore() {
        FraudAlertEntity first = alert(30, Severity.MEDIUM);
        FraudAlertEntity second = alert(60, Severity.CRITICAL);
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transaction.getId()))
            .thenReturn(List.of(second, first));

        TransactionDetailsResponseDto result = service.getDetails(transaction.getId());

        assertEquals(transaction.getId(), result.transactionId());
        assertEquals("ACC-QUERY-1", result.accountId());
        assertEquals(new BigDecimal("125.50"), result.amount());
        assertEquals(90, result.totalRiskScore());
        assertEquals(Severity.CRITICAL, result.overallSeverity());
        assertTrue(result.isFraudulent());
        assertEquals(2, result.alerts().size());
    }

    @Test
    void returnsLowSeverityWhenThereAreNoAlerts() {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transaction.getId()))
            .thenReturn(List.of());

        TransactionDetailsResponseDto result = service.getDetails(transaction.getId());

        assertEquals(0, result.totalRiskScore());
        assertEquals(Severity.LOW, result.overallSeverity());
        assertFalse(result.isFraudulent());
        assertTrue(result.alerts().isEmpty());
    }

    @Test
    void usesTheRiskScoreWhenTheAlertSeverityIsLower() {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transaction.getId()))
            .thenReturn(List.of(alert(80, Severity.LOW)));

        TransactionDetailsResponseDto result = service.getDetails(transaction.getId());

        assertEquals(Severity.CRITICAL, result.overallSeverity());
    }

    @Test
    void mapsHighAndMediumRiskScoreThresholds() {
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transaction.getId()))
            .thenReturn(List.of(alert(50, Severity.LOW), alert(25, Severity.LOW)));

        TransactionDetailsResponseDto high = service.getDetails(transaction.getId());

        assertEquals(Severity.HIGH, high.overallSeverity());

        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transaction.getId()))
            .thenReturn(List.of(alert(25, Severity.LOW)));

        TransactionDetailsResponseDto medium = service.getDetails(transaction.getId());

        assertEquals(Severity.MEDIUM, medium.overallSeverity());
    }

    @Test
    void throwsWhenTheTransactionDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(transactionRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(TransactionNotFoundException.class, () -> service.getDetails(missing));
    }

    private FraudAlertEntity alert(int riskScore, Severity severity) {
        return FraudAlertEntity.builder()
            .id(UUID.randomUUID())
            .transactionId(transaction.getId())
            .accountId(transaction.getAccountId())
            .ruleName("TestRule")
            .riskScore(riskScore)
            .severity(severity)
            .reason("Test alert")
            .flaggedAt(Instant.now())
            .build();
    }
}
