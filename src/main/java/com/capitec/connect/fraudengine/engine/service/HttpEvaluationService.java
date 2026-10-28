package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.RateLimitExceededException;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HttpEvaluationService {

    private final FraudEvaluationService fraudEvaluationService;
    private final FraudMetrics fraudMetrics;

    @RateLimiter(name = "fraudEngine", fallbackMethod = "rateLimited")
    public EvaluationOutcome evaluate(TransactionRequestDto dto) {
        return fraudEvaluationService.evaluate(dto);
    }

    EvaluationOutcome rateLimited(TransactionRequestDto dto, RequestNotPermitted ex) {
        log.warn("Rate limit rejected transaction evaluation for {}", dto.id());
        fraudMetrics.recordRejection("rate_limit");
        throw new RateLimitExceededException(ex);
    }
}
