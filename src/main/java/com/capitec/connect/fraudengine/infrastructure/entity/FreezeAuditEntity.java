package com.capitec.connect.fraudengine.infrastructure.entity;

import com.capitec.connect.fraudengine.domain.model.Severity;
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
@Table(name = "freeze_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeAuditEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "total_risk_score", nullable = false)
    private int totalRiskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_severity", nullable = false, length = 20)
    private Severity overallSeverity;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
