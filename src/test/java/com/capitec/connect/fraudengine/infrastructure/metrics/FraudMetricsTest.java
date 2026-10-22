package com.capitec.connect.fraudengine.infrastructure.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudMetricsTest {

    private PrometheusMeterRegistry meterRegistry;
    private FraudMetrics fraudMetrics;

    @BeforeEach
    void setUp() {
        // The Prometheus registry supports the histogram that the simple registry does not.
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        fraudMetrics = new FraudMetrics(meterRegistry);
    }

    @Test
    void aCompletedEvaluationIsCountedByOutcome() {
        fraudMetrics.recordEvaluation(fraudMetrics.startEvaluation(), true);
        fraudMetrics.recordEvaluation(fraudMetrics.startEvaluation(), false);

        assertEquals(1, counter("fraud.evaluation", "outcome", "fraudulent"));
        assertEquals(1, counter("fraud.evaluation", "outcome", "clean"));
        assertTrue(meterRegistry.get("fraud.evaluation.duration").timer().count() == 2);
    }

    @Test
    void rejectionsAreCountedByReason() {
        fraudMetrics.recordRejection("rate_limit");
        fraudMetrics.recordRejection("lock_timeout");
        fraudMetrics.recordRejection("lock_timeout");

        assertEquals(1, counter("fraud.evaluation.rejections", "reason", "rate_limit"));
        assertEquals(2, counter("fraud.evaluation.rejections", "reason", "lock_timeout"));
    }

    @Test
    void freezeDecisionsCasesAndPublishesAreCounted() {
        fraudMetrics.recordFreezeDecision(true);
        fraudMetrics.recordFreezeDecision(false);
        fraudMetrics.recordCaseCreated();
        fraudMetrics.recordOutboxPublish("published");

        assertEquals(1, counter("fraud.freeze.decisions", "decision", "frozen"));
        assertEquals(1, counter("fraud.freeze.decisions", "decision", "skipped"));
        assertEquals(1, counter("fraud.cases.created", null, null));
        assertEquals(1, counter("fraud.outbox.publish", "result", "published"));
    }

    @Test
    void theEvaluationTimerExposesPercentilesAndAHistogram() {
        fraudMetrics.recordEvaluation(fraudMetrics.startEvaluation(), false);

        var timer = meterRegistry.get("fraud.evaluation.duration").timer();
        assertNotNull(timer);
        assertTrue(timer.takeSnapshot().percentileValues().length > 0, "client-side percentiles are missing");
        assertTrue(timer.takeSnapshot().histogramCounts().length >= 1, "the server-side histogram is missing");
    }

    private double counter(String name, String tagKey, String tagValue) {
        var search = meterRegistry.find(name);
        if (tagKey != null) {
            search = search.tag(tagKey, tagValue);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }
}
