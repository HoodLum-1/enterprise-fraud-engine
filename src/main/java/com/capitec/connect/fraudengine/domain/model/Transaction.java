package com.capitec.connect.fraudengine.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Transaction(
    UUID id,
    String accountId,
    BigDecimal amount,
    String currency,
    String category,
    String merchantName,
    String location,
    Instant timestamp
) {
    public Transaction {
        Objects.requireNonNull(id, "Transaction ID must not be null");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID must not be null or blank");
        }
        Objects.requireNonNull(amount, "Amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be null or blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Category must not be null or blank");
        }
        Objects.requireNonNull(timestamp, "Timestamp must not be null");
    }
}
