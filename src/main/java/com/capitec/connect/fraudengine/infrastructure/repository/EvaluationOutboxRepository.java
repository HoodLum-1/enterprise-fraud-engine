package com.capitec.connect.fraudengine.infrastructure.repository;

import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EvaluationOutboxRepository extends JpaRepository<EvaluationOutboxEntity, UUID> {
    long countByEventType(String eventType);

    @Query(value = """
        SELECT * FROM event_outbox
        WHERE published_at IS NULL
          AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
        ORDER BY created_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<EvaluationOutboxEntity> lockPending(@Param("limit") int limit);
}
