package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.EvaluationUnavailableException;
import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
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
     * Evaluates a transaction for fraud with circuit breaker protection.
     * Rejects requests when the engine is unavailable rather than returning an unverified result.
     *
     * <p>There is no rate limiter here on purpose. The HTTP quota is enforced by
     * {@link HttpEvaluationService}; the Kafka ingestion path calls this method directly and must
     * not be rejected by, or consume, that quota.
     *
     * <p>The isolation level is pinned to READ COMMITTED, which is the PostgreSQL default. The
     * account lock serialises concurrent evaluations of the same account, and lock-then-read is
     * only correct under READ COMMITTED: under REPEATABLE READ the second evaluation would take a
     * snapshot taken before the first one committed and would not see the transaction it is
     * supposed to be compared against.
     *
     * @param dto the transaction request
     * @return evaluation summary
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @CircuitBreaker(name = "fraudEngine", fallbackMethod = "evaluateFallback")
    public EvaluationSummary evaluate(TransactionRequestDto dto) {
        log.info("Processing transaction evaluation for id: {}, accountId: {}",
            dto.id(), LogMasking.mask(dto.accountId()));
        Timer.Sample sample = fraudMetrics.startEvaluation();

        // Taken before the duplicate check and the history read so that two concurrent evaluations
        // of the same account cannot both read a history that excludes the other's write.
        accountLock.lockForEvaluation(dto.accountId());

        if (transactionRepository.existsById(dto.id())) {
            log.warn("Duplicate transaction detected with id: {}", dto.id());
            throw new DuplicateTransactionException(dto.id());
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

        return summary;
    }

    /**
     * Fail closed when resilience controls reject evaluation or the engine is unavailable.
     *
     * @param dto the transaction request
     * @param ex the exception that triggered the fallback
     * @return evaluation summary
     */
    EvaluationSummary evaluateFallback(TransactionRequestDto dto, Exception ex) {
        if (ex instanceof DuplicateTransactionException duplicate) {
            throw duplicate;
        }
        if (ex instanceof UnsupportedCurrencyException unsupportedCurrency) {
            throw unsupportedCurrency;
        }
        if (ex instanceof AccountLockTimeoutException lockTimeout) {
            // Contention on one account must not open the circuit for every other caller, so the
            // timeout is re-thrown unchanged and mapped to 503 by the exception handler.
            fraudMetrics.recordRejection("lock_timeout");
            throw lockTimeout;
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
}