package com.capitec.connect.fraudengine.infrastructure.metrics;

import com.capitec.connect.fraudengine.domain.model.OutboxStatus;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges describing the state of the outbox backlog.
 *
 * <p>The values are refreshed on a schedule rather than read at scrape time, so a slow or
 * unavailable database cannot stall a Prometheus scrape.
 */
@Component
@Slf4j
public class OutboxMetrics {

    private final EvaluationOutboxRepository outboxRepository;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public OutboxMetrics(EvaluationOutboxRepository outboxRepository, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("fraud.outbox.pending", pending, AtomicLong::get)
            .description("Outbox rows waiting to be published")
            .register(meterRegistry);
        Gauge.builder("fraud.outbox.parked", parked, AtomicLong::get)
            .description("Outbox rows parked as FAILED after exhausting their retries")
            .register(meterRegistry);
        Gauge.builder("fraud.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds, AtomicLong::get)
            .description("Age of the oldest outbox row that is still waiting to be published")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${fraud.engine.messaging.outbox-metrics-refresh-ms:10000}",
        initialDelayString = "${fraud.engine.messaging.outbox-metrics-refresh-ms:10000}")
    public void refresh() {
        try {
            pending.set(outboxRepository.countByStatus(OutboxStatus.PENDING));
            parked.set(outboxRepository.countByStatus(OutboxStatus.FAILED));
            Instant oldest = outboxRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING);
            oldestPendingAgeSeconds.set(oldest == null
                ? 0
                : Duration.between(oldest, Instant.now()).toSeconds());
        } catch (Exception ex) {
            // Keep the previous values; a stale gauge is better than a broken scrape.
            log.warn("Could not refresh the outbox metrics: {}", ex.getMessage());
        }
    }
}
