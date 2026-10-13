package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.messaging.FraudEventProducer;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final FraudEventProducer fraudEventProducer;
    private final AccountHistoryCacheService accountHistoryCacheService;

    /**
     * Evaluates a transaction for fraud with circuit breaker and rate limiting protection.
     * Falls back to basic evaluation if the fraud engine is unavailable.
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
        TransactionEntity transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        Instant historyCutoff = Instant.now().minus(engineProperties.getEvaluation().getHistoryWindow());
        accountHistoryCacheService.evictAccountHistoryCache(dto.accountId(), historyCutoff);
        List<Transaction> accountHistory = accountHistoryCacheService.getCachedAccountHistory(dto.accountId(), historyCutoff);

        EvaluationSummary summary = fraudEngine.evaluate(transaction, accountHistory);

        if (summary.alerts() != null && !summary.alerts().isEmpty()) {
            List<FraudAlertEntity> alertEntities = summary.alerts().stream()
                .map(fraudAlertMapper::toEntity)
                .toList();
            fraudAlertRepository.saveAll(alertEntities);
            log.info("Persisted {} fraud alert(s) for transaction id: {}", alertEntities.size(), dto.id());
        }

        fraudEventProducer.publishEvaluationEvent(transaction, summary);

        return summary;
    }

    /**
     * Fallback method for circuit breaker.
     * Performs basic evaluation without rule engine if the engine is unavailable.
     * Runs in a separate transaction (REQUIRES_NEW) because:
     * - If main method throws: main transaction rolls back, fallback runs in new transaction
     * - If circuit breaker is OPEN: main method not executed, fallback runs in new transaction
     * Package-private to prevent direct invocation.
     *
     * @param dto the transaction request
     * @param ex the exception that triggered the fallback
     * @return basic evaluation summary
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    EvaluationSummary evaluateFallback(TransactionRequestDto dto, Exception ex) {
        log.warn("Circuit breaker triggered for fraud engine, using fallback evaluation for transaction: {}. Reason: {}",
            dto.id(), ex.getMessage());

        if (transactionRepository.existsById(dto.id())) {
            log.warn("Duplicate transaction detected in fallback with id: {}", dto.id());
            throw new DuplicateTransactionException(dto.id());
        }

        Transaction transaction = transactionMapper.toDomain(dto);
        TransactionEntity transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        EvaluationSummary fallbackSummary = buildFallbackSummary(transaction);

        fraudEventProducer.publishEvaluationEvent(transaction, fallbackSummary);

        return fallbackSummary;
    }

    /**
     * Builds a minimal evaluation summary when the fraud engine is unavailable.
     * Only checks the high-amount threshold as a basic safeguard.
     */
    private EvaluationSummary buildFallbackSummary(Transaction transaction) {
        boolean isHighAmount = transaction.amount() != null
            && transaction.amount().compareTo(java.math.BigDecimal.valueOf(50000)) > 0;

        int riskScore = isHighAmount ? 40 : 0;
        com.capitec.connect.fraudengine.domain.model.Severity severity =
            isHighAmount ? com.capitec.connect.fraudengine.domain.model.Severity.HIGH
                : com.capitec.connect.fraudengine.domain.model.Severity.LOW;

        return new EvaluationSummary(
            transaction.id(),
            transaction.accountId(),
            riskScore,
            severity,
            List.of(),
            List.of()
        );
    }
}