package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.messaging.FraudEventProducer;

import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudEvaluationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FraudAlertRepository fraudAlertRepository;

    @Mock
    private FraudEngine fraudEngine;

    @Mock
    private AccountHistoryCacheService accountHistoryCacheService;

    @Mock
    private FraudEventProducer fraudEventProducer;

    @Spy
    private TransactionMapper transactionMapper = new TransactionMapper();

    @Spy
    private FraudAlertMapper fraudAlertMapper = new FraudAlertMapper();

    @Spy
    private FraudEngineProperties engineProperties = new FraudEngineProperties();

    @InjectMocks
    private FraudEvaluationService fraudEvaluationService;

    private TransactionRequestDto requestDto;
    private UUID txId;

    @BeforeEach
    void setUp() {
        requestDto = FraudTestDataFactory.transactionRequest()
                .amount(new BigDecimal("60000.00"))
                .build();
        txId = requestDto.id();
    }

    @Test
    void testEvaluate_ThrowsDuplicateTransactionException_WhenTransactionExists() {
        when(transactionRepository.existsById(txId)).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> fraudEvaluationService.evaluate(requestDto));

        verify(transactionRepository).existsById(txId);
        verify(transactionRepository, never()).save(any());
        verify(fraudEngine, never()).evaluate(any(), any());
        verify(fraudAlertRepository, never()).saveAll(any());
    }

    @Test
    void testEvaluate_SuccessfulEvaluation_WithAlertsPersisted() {
        when(transactionRepository.existsById(txId)).thenReturn(false);

        TransactionEntity historyEntity = FraudTestDataFactory.transactionEntity()
                .accountId("ACC-1001")
                .amount(new BigDecimal("200.00"))
                .category("GROCERY")
                .merchantName("Woolworths")
                .timestamp(Instant.now().minusSeconds(3600))
                .build();

        Transaction historyDomain = transactionMapper.toDomain(historyEntity);

        when(accountHistoryCacheService.getCachedAccountHistory(eq("ACC-1001"), any(Instant.class)))
                .thenReturn(List.of(historyDomain));

        FraudAlert alert = FraudTestDataFactory.fraudAlert()
                .transactionId(txId)
                .accountId("ACC-1001")
                .riskScore(40)
                .severity(Severity.HIGH)
                .reason("Amount exceeds threshold")
                .build();

        RuleEvaluationResult result = new RuleEvaluationResult("HighAmountRule", true, 40, Severity.HIGH, "Amount exceeds threshold");

        EvaluationSummary expectedSummary = FraudTestDataFactory.evaluationSummary(
                txId,
                "ACC-1001",
                40,
                Severity.HIGH,
                List.of(result),
                List.of(alert)
        );

        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(expectedSummary);

        EvaluationSummary actualSummary = fraudEvaluationService.evaluate(requestDto);

        assertNotNull(actualSummary);
        assertEquals(txId, actualSummary.transactionId());
        assertEquals("ACC-1001", actualSummary.accountId());
        assertEquals(40, actualSummary.totalRiskScore());
        assertEquals(Severity.HIGH, actualSummary.overallSeverity());
        assertTrue(actualSummary.isFraudulent());
        assertEquals(1, actualSummary.alerts().size());

        verify(transactionRepository).save(any(TransactionEntity.class));
        verify(fraudAlertRepository).saveAll(anyList());
    }

    @Test
    void testEvaluate_SuccessfulEvaluation_WithoutAlerts() {
        when(transactionRepository.existsById(txId)).thenReturn(false);
        when(accountHistoryCacheService.getCachedAccountHistory(eq("ACC-1001"), any(Instant.class)))
                .thenReturn(List.of());

        EvaluationSummary expectedSummary = FraudTestDataFactory.evaluationSummary(
                txId,
                "ACC-1001",
                0,
                Severity.LOW,
                List.of(),
                List.of()
        );

        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(expectedSummary);

        EvaluationSummary actualSummary = fraudEvaluationService.evaluate(requestDto);

        assertNotNull(actualSummary);
        assertFalse(actualSummary.isFraudulent());
        assertEquals(0, actualSummary.alerts().size());

        verify(transactionRepository).save(any(TransactionEntity.class));
        verify(fraudAlertRepository, never()).saveAll(any());
    }

    @Test
    void testEvaluate_UsesConfiguredHistoryWindow() {
        when(transactionRepository.existsById(txId)).thenReturn(false);
        when(accountHistoryCacheService.getCachedAccountHistory(eq("ACC-1001"), any(Instant.class)))
                .thenReturn(List.of());
        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(
                FraudTestDataFactory.evaluationSummary(txId, "ACC-1001", 0, Severity.LOW, List.of(), List.of())
        );

        engineProperties.getEvaluation().setHistoryWindow(Duration.ofHours(2));

        Instant before = Instant.now();
        fraudEvaluationService.evaluate(requestDto);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(accountHistoryCacheService).getCachedAccountHistory(eq("ACC-1001"), cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        assertFalse(cutoff.isBefore(before.minus(Duration.ofHours(2))), "cutoff should not be earlier than (start - 2h)");
        assertFalse(cutoff.isAfter(after.minus(Duration.ofHours(2))), "cutoff should not be later than (end - 2h)");
    }

    @Test
    void historyWindowDefaultsTo24Hours() {
        assertEquals(Duration.ofHours(24), new FraudEngineProperties().getEvaluation().getHistoryWindow());
    }
}