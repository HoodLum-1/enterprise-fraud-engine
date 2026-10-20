package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.EvaluationUnavailableException;
import com.capitec.connect.fraudengine.api.exception.RateLimitExceededException;
import com.capitec.connect.fraudengine.api.exception.UnsupportedCurrencyException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Evaluates a transaction for fraud with circuit breaker and rate limiting protection.
     * Rejects requests when the engine is unavailable rather than returning an unverified result.
     *
     * @param dto the transaction request
     * @return evaluation summary
     */
    @Transactional
    @CircuitBreaker(name = "fraudEngine", fallbackMethod = "evaluateFallback")
    @RateLimiter(name = "fraudEngine")
    public EvaluationSummary evaluate(TransactionRequestDto dto) {
        log.info("Processing transaction evaluation for id: {}, accountId: {}", dto.id(), dto.accountId());

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
        if (ex instanceof RequestNotPermitted) {
            log.warn("Rate limit rejected transaction evaluation for {}", dto.id());
            throw new RateLimitExceededException(ex);
        }
        if (ex instanceof DuplicateTransactionException duplicate) {
            throw duplicate;
        }
        if (ex instanceof UnsupportedCurrencyException unsupportedCurrency) {
            throw unsupportedCurrency;
        }
        if (ex instanceof CallNotPermittedException) {
            log.warn("Circuit breaker is open for transaction evaluation {}", dto.id());
        } else {
            log.error("Fraud evaluation unavailable for transaction {}: {}", dto.id(), ex.getMessage(), ex);
        }
        throw new EvaluationUnavailableException(ex);
    }
}