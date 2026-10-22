package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.OutboxStatus;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional operations on the outbox.
 *
 * <p>Each operation runs in its own short transaction. The publisher deliberately does not hold a
 * database transaction open while it waits for Kafka: a batch of blocking sends inside one
 * transaction would hold a connection - and the row locks of the whole batch - for the duration of
 * the slowest send.
 */
@Component
@Slf4j
public class OutboxStore {

    private final EvaluationOutboxRepository repository;
    private final TransactionTemplate transactionTemplate;

    public OutboxStore(EvaluationOutboxRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Claims up to {@code limit} pending rows and leases them for the given duration.
     *
     * <p>Claiming counts as an attempt, so a publisher that repeatedly dies mid-publish cannot
     * retry a row forever.
     */
    public List<EvaluationOutboxEntity> claim(int limit, Duration lease) {
        List<EvaluationOutboxEntity> claimed = transactionTemplate.execute(status -> {
            List<EvaluationOutboxEntity> rows = repository.claimPending(limit);
            Instant now = Instant.now();
            Instant leaseExpiresAt = now.plus(lease);
            for (EvaluationOutboxEntity row : rows) {
                row.setClaimedAt(now);
                row.setLeaseExpiresAt(leaseExpiresAt);
                row.setAttempts(row.getAttempts() + 1);
            }
            return List.copyOf(rows);
        });
        return claimed == null ? List.of() : claimed;
    }

    /** Marks a row as delivered. */
    public void acknowledge(UUID id) {
        transactionTemplate.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
            row.setStatus(OutboxStatus.PUBLISHED);
            row.setPublishedAt(Instant.now());
            row.setNextAttemptAt(null);
            row.setLastError(null);
            row.setClaimedAt(null);
            row.setLeaseExpiresAt(null);
        }));
    }

    /** Releases the claim and schedules the next attempt. */
    public void reschedule(UUID id, Instant nextAttemptAt, String error) {
        transactionTemplate.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
            row.setNextAttemptAt(nextAttemptAt);
            row.setLastError(error);
            row.setClaimedAt(null);
            row.setLeaseExpiresAt(null);
        }));
    }

    /**
     * Parks a row that exhausted its retry budget. Parked rows are never claimed again, so they
     * stay visible for investigation instead of being retried forever.
     */
    public void park(UUID id, String error) {
        transactionTemplate.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
            row.setStatus(OutboxStatus.FAILED);
            row.setNextAttemptAt(null);
            row.setLastError(error);
            row.setClaimedAt(null);
            row.setLeaseExpiresAt(null);
        }));
    }

    /** Removes published rows older than the cutoff, in a bounded batch. */
    public int prunePublishedBefore(Instant cutoff, int batchSize) {
        Integer removed = transactionTemplate.execute(status -> repository.deletePublishedBefore(cutoff, batchSize));
        return removed == null ? 0 : removed;
    }
}
