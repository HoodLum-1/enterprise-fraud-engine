package com.capitec.connect.fraudengine.infrastructure.entity;

import com.capitec.connect.fraudengine.domain.model.Severity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_cases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "total_risk_score", nullable = false)
    private int totalRiskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_severity", nullable = false, length = 20)
    private Severity overallSeverity;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    public void updateStatus(String newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
}