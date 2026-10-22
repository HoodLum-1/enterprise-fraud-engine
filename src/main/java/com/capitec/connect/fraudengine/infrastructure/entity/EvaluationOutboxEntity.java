package com.capitec.connect.fraudengine.infrastructure.entity;

import com.capitec.connect.fraudengine.domain.model.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationOutboxEntity {

    @Id
    private UUID id;

    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    /** When the row was claimed by a publisher. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * When the claim expires. A publisher that dies mid-publish leaves the row claimed until the
     * lease expires, after which another publisher may claim it again.
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
}
