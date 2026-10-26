package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.EvaluationUnavailableException;
import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
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
     * Evaluates a transaction for fraud with circuit breaker protection.
     * Rejects requests when the engine is unavailable rather than returning an unverified result.
     *
     * <p>A request whose transaction id is already stored is answered from what is stored rather
     * than from a second evaluation: the caller either retried after not seeing the first response,
     * or lost a race with a concurrent submission of the same id, and both deserve the answer the
     * first call produced. Only when the stored transaction is a different one does the id become
     * a conflict. See {@link #replayOrConflict}.
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
     * @return the evaluation, and whether this call stored the transaction
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @CircuitBreaker(name = "fraudEngine", fallbackMethod = "evaluateFallback")
    public EvaluationOutcome evaluate(TransactionRequestDto dto) {
        log.info("Processing transaction evaluation for id: {}, accountId: {}",
            dto.id(), LogMasking.mask(dto.accountId()));
        Timer.Sample sample = fraudMetrics.startEvaluation();

        // Taken before the duplicate check and the history read so that two concurrent evaluations
        // of the same account cannot both read a history that excludes the other's write. It is
        // also what makes a repeated id deterministic rather than a race: two submissions of one
        // id are two submissions of one account, so the second waits here for the first to commit.
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

    /**
     * Answers a request whose transaction id is already stored.
     *
     * <p>The stored transaction is compared with the request on the values the rules read, not on
     * the raw payload, so that a retry which re-serialised an amount or re-cased a category is
     * still recognised as the same request. A request that genuinely describes a different
     * transaction under the same id is refused: accepting it would silently replace a stored
     * transaction and every alert evaluated from it.
     *
     * @param stored the transaction already stored under this id
     * @param dto the request being answered
     * @return the evaluation that is stored
     * @throws DuplicateTransactionException if the stored transaction is a different one
     */
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

    /**
     * Rebuilds the evaluation of a stored transaction from the transaction and its alerts.
     *
     * <p>The evaluation itself is not persisted, because the alerts are the durable part of it: one
     * alert per triggered rule carries the rule, its points and its severity, which is everything
     * the summary was made of. Rebuilding from them keeps a replay byte-identical to what the
     * original call returned and to what {@code GET /transactions/{id}} reports, without storing a
     * second copy of a value that is already derivable.
     *
     * @param stored the stored transaction
     * @return the evaluation that produced the stored alerts
     */
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

    /**
     * Fail closed when resilience controls reject evaluation or the engine is unavailable.
     *
     * @param dto the transaction request
     * @param ex the exception that triggered the fallback
     * @return the evaluation, and whether this call stored the transaction
     */
    EvaluationOutcome evaluateFallback(TransactionRequestDto dto, Exception ex) {
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
        if (ex instanceof DataIntegrityViolationException integrity) {
            // Two submissions of one id both got past the duplicate check, so the insert lost the
            // race. The transaction has already been rolled back by the time this runs, which is
            // what makes the row readable again: answer with it if it holds what was asked for,
            // and refuse if it holds something else. Reporting this as an unavailable engine
            // would tell a caller that retried twice its evaluation never happened.
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

    /**
     * Answers the loser of a race to insert one transaction id.
     *
     * @param dto the transaction request that lost the race
     * @param integrity the failure the losing insert raised
     * @return the stored evaluation, when the winner stored what this request asked for
     * @throws DuplicateTransactionException if the stored transaction is a different one, or
     *                                       cannot be read back to tell
     */
    private EvaluationOutcome resolveLostDuplicate(TransactionRequestDto dto,
                                                   DataIntegrityViolationException integrity) {
        fraudMetrics.recordRejection("duplicate");
        TransactionEntity stored;
        try {
            stored = transactionRepository.findById(dto.id()).orElse(null);
        } catch (RuntimeException readFailure) {
            // The insert failed because a row with this id is already there, so a conflict is the
            // honest answer even when the row itself cannot be read back.
            log.warn("Could not read back transaction {} after a conflicting write: {}",
                dto.id(), readFailure.getMessage());
            throw new DuplicateTransactionException(dto.id());
        }
        if (stored == null) {
            // Not a duplicate after all: some other constraint failed, and the existing handler
            // reports it as a conflict with existing data rather than as an unavailable engine.
            throw integrity;
        }
        return EvaluationOutcome.replayed(replayOrConflict(stored, dto));
    }
}