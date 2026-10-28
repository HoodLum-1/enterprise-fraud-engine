package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.EvaluationUnavailableException;
import com.capitec.connect.fraudengine.domain.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.SeverityAssessment;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.logging.LogMasking;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudEvaluationService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudEngine fraudEngine;
    private final TransactionMapper transactionMapper;
    private final FraudAlertMapper fraudAlertMapper;
    private final FraudEngineProperties engineProperties;
    private final EvaluationOutboxService evaluationOutboxService;
    private final AccountHistoryService accountHistoryService;
    private final AccountLock accountLock;
    private final FraudMetrics fraudMetrics;

    /**
     * Rejects requests when the engine is unavailable rather than returning an unverified result.
     *
     * @param dto the transaction request
     * @return the evaluation, and whether this call stored the transaction
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @CircuitBreaker(name = "fraudEngine", fallbackMethod = "evaluateFallback")
    public EvaluationOutcome evaluate(TransactionRequestDto dto) {
        log.info("Processing transaction evaluation for id: {}, accountId: {}",
            dto.id(), LogMasking.mask(dto.accountId()));
        Timer.Sample sample = fraudMetrics.startEvaluation();
        accountLock.lockForEvaluation(dto.accountId());

        TransactionEntity stored = transactionRepository.findById(dto.id()).orElse(null);
        if (stored != null) {
            return EvaluationOutcome.replayed(replayOrConflict(stored, dto));
        }

        Transaction transaction = transactionMapper.toDomain(dto);
        Instant historyCutoff = transaction.timestamp().minus(engineProperties.getEvaluation().getHistoryWindow());
        List<Transaction> accountHistory = accountHistoryService.findHistory(
            transaction.accountId(), historyCutoff, transaction.timestamp()).stream()
            .filter(previous -> !previous.id().equals(transaction.id()))
            .toList();

        EvaluationSummary summary = fraudEngine.evaluate(transaction, accountHistory);
        TransactionEntity transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        if (summary.alerts() != null && !summary.alerts().isEmpty()) {
            List<FraudAlertEntity> alertEntities = summary.alerts().stream()
                .map(fraudAlertMapper::toEntity)
                .toList();
            fraudAlertRepository.saveAll(alertEntities);
            log.info("Persisted {} fraud alert(s) for transaction id: {}", alertEntities.size(), dto.id());
        }

        evaluationOutboxService.enqueueEvaluation(transaction, summary);
        fraudMetrics.recordEvaluation(sample, summary.isFraudulent());

        return EvaluationOutcome.created(summary);
    }

    private EvaluationSummary replayOrConflict(TransactionEntity stored, TransactionRequestDto dto) {
        Transaction incoming = transactionMapper.toDomain(dto);
        Transaction existing = transactionMapper.toDomain(stored);

        if (!existing.isSameTransactionAs(incoming)) {
            log.warn("Transaction id {} already holds a different transaction; refusing to replace it", dto.id());
            throw new DuplicateTransactionException(dto.id());
        }

        EvaluationSummary summary = storedEvaluation(stored);
        log.info("Replaying stored evaluation for transaction id {} ({} alert(s), risk score {})",
            dto.id(), summary.alerts().size(), summary.totalRiskScore());
        return summary;
    }

    private EvaluationSummary storedEvaluation(TransactionEntity stored) {
        List<FraudAlert> alerts = fraudAlertRepository
            .findByTransactionIdOrderByFlaggedAtDesc(stored.getId()).stream()
            .map(fraudAlertMapper::toDomain)
            .toList();

        int totalRiskScore = alerts.stream().mapToInt(FraudAlert::riskScore).sum();
        Severity overallSeverity = SeverityAssessment.assess(totalRiskScore,
            alerts.stream().map(FraudAlert::severity).toList());
        List<RuleEvaluationResult> triggeredRules = alerts.stream()
            .map(alert -> new RuleEvaluationResult(
                alert.ruleName(), true, alert.riskScore(), alert.severity(), alert.reason()))
            .toList();

        return new EvaluationSummary(
            stored.getId(),
            stored.getAccountId(),
            totalRiskScore,
            overallSeverity,
            triggeredRules,
            alerts);
    }

    EvaluationOutcome evaluateFallback(TransactionRequestDto dto, Exception ex) {
        if (ex instanceof DuplicateTransactionException duplicate) {
            throw duplicate;
        }
        if (ex instanceof UnsupportedCurrencyException unsupportedCurrency) {
            throw unsupportedCurrency;
        }
        if (ex instanceof AccountLockTimeoutException lockTimeout) {
            fraudMetrics.recordRejection("lock_timeout");
            throw lockTimeout;
        }
        if (ex instanceof DataIntegrityViolationException integrity) {
            return resolveLostDuplicate(dto, integrity);
        }
        if (ex instanceof CallNotPermittedException) {
            log.warn("Circuit breaker is open for transaction evaluation {}", dto.id());
            fraudMetrics.recordRejection("circuit_open");
        } else {
            log.error("Fraud evaluation unavailable for transaction {}: {}", dto.id(), ex.getMessage(), ex);
            fraudMetrics.recordRejection("engine_unavailable");
        }
        throw new EvaluationUnavailableException(ex);
    }

    private EvaluationOutcome resolveLostDuplicate(TransactionRequestDto dto,
                                                   DataIntegrityViolationException integrity) {
        fraudMetrics.recordRejection("duplicate");
        TransactionEntity stored;
        try {
            stored = transactionRepository.findById(dto.id()).orElse(null);
        } catch (RuntimeException readFailure) {
            log.warn("Could not read back transaction {} after a conflicting write: {}",
                dto.id(), readFailure.getMessage());
            throw new DuplicateTransactionException(dto.id());
        }
        if (stored == null) {
            throw integrity;
        }
        return EvaluationOutcome.replayed(replayOrConflict(stored, dto));
    }
}