package com.capitec.connect.fraudengine.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "merchant_name", length = 100)
    private String merchantName;

    @Column(length = 100)
    private String location;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
