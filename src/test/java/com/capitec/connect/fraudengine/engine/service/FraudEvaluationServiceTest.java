package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.capitec.connect.fraudengine.infrastructure.messaging.FraudEventProducer;

import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import com.capitec.connect.fraudengine.api.exception.EvaluationUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private AccountHistoryService accountHistoryService;

    @Mock
    private EvaluationOutboxService evaluationOutboxService;

    @Mock
    private AccountLock accountLock;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private FraudMetrics fraudMetrics = new FraudMetrics(meterRegistry);

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
    void aRetryOfTheSameTransactionReplaysTheStoredEvaluationInsteadOfEvaluatingAgain() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(txId)).thenReturn(List.of(
            alertEntity(txId, "HighAmountRule", 40, Severity.HIGH),
            alertEntity(txId, "TransactionVelocityRule", 25, Severity.MEDIUM)));

        EvaluationOutcome outcome = fraudEvaluationService.evaluate(requestDto);

        assertFalse(outcome.created(), "a retry must not be reported as a new transaction");
        assertEquals(txId, outcome.summary().transactionId());
        assertEquals("ACC-1001", outcome.summary().accountId());
        assertEquals(65, outcome.summary().totalRiskScore());
        assertEquals(Severity.HIGH, outcome.summary().overallSeverity());
        assertTrue(outcome.summary().isFraudulent());
        assertEquals(2, outcome.summary().alerts().size());
        assertEquals(2, outcome.summary().triggeredRules().size());

        verify(transactionRepository, never()).save(any());
        verify(fraudEngine, never()).evaluate(any(), any());
        verify(fraudAlertRepository, never()).saveAll(any());
        verify(evaluationOutboxService, never()).enqueueEvaluation(any(), any());
    }

    @Test
    void aReplayOfATransactionWithNoFindingsIsStillTheStoredCleanEvaluation() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(txId)).thenReturn(List.of());

        EvaluationOutcome outcome = fraudEvaluationService.evaluate(requestDto);

        assertFalse(outcome.created());
        assertEquals(0, outcome.summary().totalRiskScore());
        assertEquals(Severity.LOW, outcome.summary().overallSeverity());
        assertFalse(outcome.summary().isFraudulent());
        assertTrue(outcome.summary().alerts().isEmpty());
        assertTrue(outcome.summary().triggeredRules().isEmpty());
    }

    @Test
    void theSameIdHoldingADifferentTransactionIsRefusedRatherThanReplaced() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        stored.setAmount(new BigDecimal("1.00"));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));

        assertThrows(DuplicateTransactionException.class, () -> fraudEvaluationService.evaluate(requestDto));

        verify(transactionRepository, never()).save(any());
        verify(fraudEngine, never()).evaluate(any(), any());
        verify(fraudAlertRepository, never()).saveAll(any());
        verify(evaluationOutboxService, never()).enqueueEvaluation(any(), any());
    }

    @Test
    void theSameIdAtADifferentEventTimeIsAlsoADifferentTransaction() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        stored.setTimestamp(stored.getTimestamp().plusSeconds(1));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));

        assertThrows(DuplicateTransactionException.class, () -> fraudEvaluationService.evaluate(requestDto));

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void aLoserOfARaceToInsertOneIdIsAnsweredFromTheWinnerRatherThanReportedAsAnOutage() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(txId)).thenReturn(List.of(
            alertEntity(txId, "HighAmountRule", 40, Severity.HIGH)));

        EvaluationOutcome outcome = fraudEvaluationService.evaluateFallback(requestDto,
            new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertFalse(outcome.created(), "the loser of the race is still a replay");
        assertEquals(40, outcome.summary().totalRiskScore());
        assertEquals(Severity.HIGH, outcome.summary().overallSeverity());

        verify(evaluationOutboxService, never()).enqueueEvaluation(any(), any());
        verify(fraudAlertRepository, never()).saveAll(any());
        assertEquals(1.0, rejections("duplicate"));
        assertEquals(0.0, rejections("engine_unavailable"),
            "a client that retried must not be told its evaluation never happened");
    }

    @Test
    void aLoserWhoseWinnerStoredSomethingElseIsStillRefused() {
        TransactionEntity stored = transactionMapper.toEntity(transactionMapper.toDomain(requestDto));
        stored.setCategory("TRAVEL");
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(stored));

        assertThrows(DuplicateTransactionException.class, () -> fraudEvaluationService.evaluateFallback(
            requestDto, new DataIntegrityViolationException("duplicate key value violates unique constraint")));

        assertEquals(1.0, rejections("duplicate"));
    }

    @Test
    void aConstraintFailureThatIsNotADuplicateIsNotInventedIntoOne() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());

        DataIntegrityViolationException original =
            new DataIntegrityViolationException("value too long for type character varying(50)");

        assertThrows(DataIntegrityViolationException.class,
            () -> fraudEvaluationService.evaluateFallback(requestDto, original));

        assertEquals(1.0, rejections("duplicate"));
        assertEquals(0.0, rejections("engine_unavailable"));
    }

    @Test
    void aDuplicateThatCannotBeReadBackIsStillAConflictRatherThanAnOutage() {
        when(transactionRepository.findById(txId))
            .thenThrow(new DataAccessResourceFailureException("connection reset"));

        DuplicateTransactionException thrown = assertThrows(DuplicateTransactionException.class,
            () -> fraudEvaluationService.evaluateFallback(
                requestDto, new DataIntegrityViolationException("duplicate key value violates unique constraint")));

        assertEquals("Transaction already exists with id: " + txId, thrown.getMessage());
        assertEquals(1.0, rejections("duplicate"));
    }

    private FraudAlertEntity alertEntity(UUID transactionId, String ruleName, int riskScore, Severity severity) {
        return FraudAlertEntity.builder()
            .id(UUID.randomUUID())
            .transactionId(transactionId)
            .accountId("ACC-1001")
            .ruleName(ruleName)
            .riskScore(riskScore)
            .severity(severity)
            .reason(ruleName + " triggered")
            .flaggedAt(Instant.now())
            .build();
    }

    private double rejections(String reason) {
        var counter = meterRegistry.find("fraud.evaluation.rejections").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void testEvaluate_SuccessfulEvaluation_WithAlertsPersisted() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());

        TransactionEntity historyEntity = FraudTestDataFactory.transactionEntity()
                .accountId("ACC-1001")
                .amount(new BigDecimal("200.00"))
                .category("GROCERY")
                .merchantName("Woolworths")
                .timestamp(Instant.now().minusSeconds(3600))
                .build();

        Transaction historyDomain = transactionMapper.toDomain(historyEntity);

        when(accountHistoryService.findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class)))
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

        EvaluationSummary actualSummary = fraudEvaluationService.evaluate(requestDto).summary();

        assertNotNull(actualSummary);
        assertEquals(txId, actualSummary.transactionId());
        assertEquals("ACC-1001", actualSummary.accountId());
        assertEquals(40, actualSummary.totalRiskScore());
        assertEquals(Severity.HIGH, actualSummary.overallSeverity());
        assertTrue(actualSummary.isFraudulent());
        assertEquals(1, actualSummary.alerts().size());

        verify(transactionRepository).save(any(TransactionEntity.class));
        verify(fraudAlertRepository).saveAll(anyList());
        verify(evaluationOutboxService).enqueueEvaluation(any(Transaction.class), eq(expectedSummary));
    }

    @Test
    void testEvaluate_SuccessfulEvaluation_WithoutAlerts() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());
        when(accountHistoryService.findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class)))
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

        EvaluationSummary actualSummary = fraudEvaluationService.evaluate(requestDto).summary();

        assertNotNull(actualSummary);
        assertFalse(actualSummary.isFraudulent());
        assertEquals(0, actualSummary.alerts().size());

        verify(transactionRepository).save(any(TransactionEntity.class));
        verify(fraudAlertRepository, never()).saveAll(any());
        verify(evaluationOutboxService).enqueueEvaluation(any(Transaction.class), eq(expectedSummary));
    }

    @Test
    void testEvaluate_UsesConfiguredHistoryWindow() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());
        when(accountHistoryService.findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(
                FraudTestDataFactory.evaluationSummary(txId, "ACC-1001", 0, Severity.LOW, List.of(), List.of())
        );

        engineProperties.getEvaluation().setHistoryWindow(Duration.ofHours(2));

        Instant transactionTime = requestDto.timestamp();
        fraudEvaluationService.evaluate(requestDto);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(accountHistoryService).findHistory(eq("ACC-1001"), fromCaptor.capture(), toCaptor.capture());
        assertEquals(transactionTime.minus(Duration.ofHours(2)), fromCaptor.getValue());
        assertEquals(transactionTime, toCaptor.getValue());
    }

    @Test
    void evaluateExcludesCurrentTransactionFromHistoryAndReadsBeforeSaving() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());
        Transaction current = FraudTestDataFactory.transaction()
            .id(txId)
            .accountId("ACC-1001")
            .timestamp(requestDto.timestamp())
            .build();
        when(accountHistoryService.findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of(current));
        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(
            FraudTestDataFactory.evaluationSummary(txId, "ACC-1001", 0, Severity.LOW, List.of(), List.of())
        );

        fraudEvaluationService.evaluate(requestDto);

        ArgumentCaptor<List<Transaction>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(fraudEngine).evaluate(any(Transaction.class), historyCaptor.capture());
        assertTrue(historyCaptor.getValue().isEmpty());
        org.mockito.InOrder order = inOrder(accountHistoryService, fraudEngine, transactionRepository);
        order.verify(accountHistoryService).findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class));
        order.verify(fraudEngine).evaluate(any(Transaction.class), anyList());
        order.verify(transactionRepository).save(any(TransactionEntity.class));
    }

    @Test
    void historyWindowDefaultsTo24Hours() {
        assertEquals(Duration.ofHours(24), new FraudEngineProperties().getEvaluation().getHistoryWindow());
    }

    @Test
    void circuitBreakerFallbackReturnsUnavailableWithoutPersistingOrPublishing() {
        CallNotPermittedException openCircuit = mock(CallNotPermittedException.class);

        assertThrows(EvaluationUnavailableException.class,
            () -> fraudEvaluationService.evaluateFallback(requestDto, openCircuit));

        verify(transactionRepository, never()).save(any());
        verify(fraudAlertRepository, never()).saveAll(any());
        verifyNoInteractions(evaluationOutboxService);
    }

    @Test
    void accountLockIsTakenBeforeTheDuplicateCheckAndTheHistoryRead() {
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());
        when(accountHistoryService.findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(fraudEngine.evaluate(any(Transaction.class), anyList())).thenReturn(
            FraudTestDataFactory.evaluationSummary(txId, "ACC-1001", 0, Severity.LOW, List.of(), List.of())
        );

        fraudEvaluationService.evaluate(requestDto);

        InOrder order = inOrder(accountLock, transactionRepository, accountHistoryService);
        order.verify(accountLock).lockForEvaluation("ACC-1001");
        order.verify(transactionRepository).findById(txId);
        order.verify(accountHistoryService).findHistory(eq("ACC-1001"), any(Instant.class), any(Instant.class));
    }

    @Test
    void accountLockTimeoutIsRethrownUnchangedSoItIsNotReportedAsAnEngineOutage() {
        AccountLockTimeoutException timeout =
            new AccountLockTimeoutException("ACC-1001", new IllegalStateException("lock timeout"));

        AccountLockTimeoutException thrown = assertThrows(AccountLockTimeoutException.class,
            () -> fraudEvaluationService.evaluateFallback(requestDto, timeout));

        assertSame(timeout, thrown);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(evaluationOutboxService);
    }

    @Test
    void evaluationIsPinnedToReadCommittedIsolation() throws Exception {
        Transactional transactional = FraudEvaluationService.class
            .getMethod("evaluate", TransactionRequestDto.class)
            .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Isolation.READ_COMMITTED, transactional.isolation());
    }
}