package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.RateLimitExceededException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * HTTP entry point for transaction evaluation.
 *
 * <p>The {@code fraudEngine} rate limiter protects the HTTP API and its quota, so it belongs here
 * and not on {@link FraudEvaluationService}. Kafka ingestion calls the evaluation service
 * directly: a backlog on the topic must never spend the HTTP quota, and a burst of Kafka traffic
 * must never make the HTTP API answer 429 for a client that still has quota left.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpEvaluationService {

    private final FraudEvaluationService fraudEvaluationService;
    private final FraudMetrics fraudMetrics;

    @RateLimiter(name = "fraudEngine", fallbackMethod = "rateLimited")
    public EvaluationSummary evaluate(TransactionRequestDto dto) {
        return fraudEvaluationService.evaluate(dto);
    }

    /**
     * Fail closed when the HTTP quota is spent, without touching the database.
     *
     * @param dto the transaction request that was rejected
     * @param ex the rate limiter rejection
     * @return never returns normally
     */
    EvaluationSummary rateLimited(TransactionRequestDto dto, RequestNotPermitted ex) {
        log.warn("Rate limit rejected transaction evaluation for {}", dto.id());
        fraudMetrics.recordRejection("rate_limit");
        throw new RateLimitExceededException(ex);
    }
}
