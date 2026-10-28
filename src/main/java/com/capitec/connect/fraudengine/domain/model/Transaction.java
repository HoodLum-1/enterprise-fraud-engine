package com.capitec.connect.fraudengine.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
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

    public boolean isSameTransactionAs(Transaction other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(id, other.id)
            && Objects.equals(canonical(accountId), canonical(other.accountId))
            && amount.compareTo(other.amount) == 0
            && Objects.equals(code(currency), code(other.currency))
            && Objects.equals(code(category), code(other.category))
            && Objects.equals(canonical(merchantName), canonical(other.merchantName))
            && Objects.equals(canonical(location), canonical(other.location))
            && Objects.equals(timestamp, other.timestamp);
    }

    private static String canonical(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String code(String value) {
        String trimmed = canonical(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
