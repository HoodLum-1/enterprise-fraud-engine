package com.capitec.connect.fraudengine.infrastructure.repository;

import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EvaluationOutboxRepository extends JpaRepository<EvaluationOutboxEntity, UUID> {
    long countByEventType(String eventType);

    /**
     * Claims pending rows for publishing. The row lock is released as soon as the claiming
     * transaction commits, so the claim is expressed as a lease rather than as a held lock.
     */
    @Query(value = """
        SELECT * FROM event_outbox
        WHERE status = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
          AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
        ORDER BY created_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<EvaluationOutboxEntity> claimPending(@Param("limit") int limit);

    /**
     * Deletes published rows older than the cutoff, at most {@code batch} at a time. A single large
     * delete would hold a long transaction and lock the whole table for its duration.
     */
    @Modifying
    @Query(value = """
        DELETE FROM event_outbox
        WHERE id IN (
            SELECT id FROM event_outbox
            WHERE status = 'PUBLISHED' AND published_at < :cutoff
            ORDER BY published_at
            LIMIT :batch
        )
        """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("batch") int batch);
}
