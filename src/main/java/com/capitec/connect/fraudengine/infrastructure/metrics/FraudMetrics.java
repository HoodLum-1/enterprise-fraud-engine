package com.capitec.connect.fraudengine.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Low-cardinality metrics for the evaluation path.
 *
 * <p>Every tag here is a closed set of values. Account ids, transaction ids and client names are
 * deliberately not used as tags: they are unbounded and would blow up the metric registry.
 */
@Component
@RequiredArgsConstructor
public class FraudMetrics {

    private final MeterRegistry meterRegistry;

    public Timer.Sample startEvaluation() {
        return Timer.start(meterRegistry);
    }

    /** Records the outcome of a completed evaluation. */
    public void recordEvaluation(Timer.Sample sample, boolean fraudulent) {
        sample.stop(evaluationTimer());
        Counter.builder("fraud.evaluation")
            .tag("outcome", fraudulent ? "fraudulent" : "clean")
            .register(meterRegistry)
            .increment();
    }

    /** Records an evaluation that was rejected before it could be completed. */
    public void recordRejection(String reason) {
        Counter.builder("fraud.evaluation.rejections")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    /** Records whether the freeze policy asked for an account freeze. */
    public void recordFreezeDecision(boolean freeze) {
        Counter.builder("fraud.freeze.decisions")
            .tag("decision", freeze ? "frozen" : "skipped")
            .register(meterRegistry)
            .increment();
    }

    public void recordCaseCreated() {
        Counter.builder("fraud.cases.created").register(meterRegistry).increment();
    }

    /** Records the result of publishing a single outbox row. */
    public void recordOutboxPublish(String result) {
        Counter.builder("fraud.outbox.publish")
            .tag("result", result)
            .register(meterRegistry)
            .increment();
    }

    /**
     * The evaluation timer carries client-side percentiles so the actuator endpoint exposes them
     * directly, and the server-side histogram is enabled in the configuration so the percentiles
     * can be aggregated across instances in Prometheus.
     */
    private Timer evaluationTimer() {
        return Timer.builder("fraud.evaluation.duration")
            .description("Time spent evaluating a transaction")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(meterRegistry);
    }

    /** Convenience for callers that only need a duration. */
    public void recordDuration(String name, long duration, TimeUnit unit) {
        Timer.builder(name).register(meterRegistry).record(duration, unit);
    }
}
