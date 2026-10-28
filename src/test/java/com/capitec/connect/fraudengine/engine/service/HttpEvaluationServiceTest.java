package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.RateLimitExceededException;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpEvaluationServiceTest {

    @Mock
    private FraudEvaluationService fraudEvaluationService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private FraudMetrics fraudMetrics = new FraudMetrics(meterRegistry);

    @InjectMocks
    private HttpEvaluationService httpEvaluationService;

    private TransactionRequestDto requestDto;

    @BeforeEach
    void setUp() {
        requestDto = FraudTestDataFactory.transactionRequest()
            .amount(new BigDecimal("150.00"))
            .build();
    }

    @Test
    void itDelegatesToTheEvaluationServiceAndReturnsTheSummaryUnchanged() {
        EvaluationSummary summary = FraudTestDataFactory.evaluationSummary(
            requestDto.id(), "ACC-1001", 0, Severity.LOW, List.of(), List.of());
        EvaluationOutcome outcome = EvaluationOutcome.created(summary);
        when(fraudEvaluationService.evaluate(requestDto)).thenReturn(outcome);

        EvaluationOutcome result = httpEvaluationService.evaluate(requestDto);

        assertSame(outcome, result);
        assertSame(summary, result.summary());
        assertTrue(result.created());
        verify(fraudEvaluationService).evaluate(requestDto);
    }

    @Test
    void aReplayStaysAReplaySoTheCallerCanTellItFromAFreshEvaluation() {
        EvaluationSummary summary = FraudTestDataFactory.evaluationSummary(
            requestDto.id(), "ACC-1001", 0, Severity.LOW, List.of(), List.of());
        when(fraudEvaluationService.evaluate(requestDto))
            .thenReturn(EvaluationOutcome.replayed(summary));

        EvaluationOutcome result = httpEvaluationService.evaluate(requestDto);

        assertSame(summary, result.summary());
        assertFalse(result.created());
    }

    @Test
    void aSpentQuotaIsRejectedBeforeTheEvaluationServiceIsCalled() {
        RequestNotPermitted rejection =
            RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("fraudEngine"));

        RateLimitExceededException thrown =
            assertThrows(RateLimitExceededException.class, () -> httpEvaluationService.rateLimited(requestDto, rejection));

        // The handler needs the original rejection to map this to 429 rather than 500.
        assertSame(rejection, thrown.getCause());
        verifyNoInteractions(fraudEvaluationService);
        assertEquals(1.0, rejections("rate_limit"));
    }

    @Test
    void evaluationFailuresPropagateThroughTheFacadeUntouched() {
        DuplicateTransactionException duplicate =
            new DuplicateTransactionException(requestDto.id());
        when(fraudEvaluationService.evaluate(requestDto)).thenThrow(duplicate);

        DuplicateTransactionException thrown =
            assertThrows(DuplicateTransactionException.class, () -> httpEvaluationService.evaluate(requestDto));

        assertSame(duplicate, thrown);
    }

    private double rejections(String reason) {
        var counter = meterRegistry.find("fraud.evaluation.rejections").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
