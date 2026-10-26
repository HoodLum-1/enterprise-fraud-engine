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

    /**
     * Whether this transaction and {@code other} are the same event, ignoring how each was spelled.
     *
     * <p>This decides whether a repeated submission of an id is a retry to be answered with the
     * evaluation already stored, or a conflicting claim on that id to be refused. Getting it wrong
     * in the permissive direction silently rewrites a stored transaction, so the comparison is on
     * the values the rules actually read rather than on the raw strings: {@code 60000.00} and
     * {@code 60000.0} are the same amount, and {@code groceries} and {@code GROCERIES} are the
     * same category, because {@link #amount} is compared numerically and the rules already
     * uppercase and trim the category before matching on it.
     *
     * <p>The event time is compared exactly. Two transactions at different times are different
     * events even if every other field matches, and treating them as one would drop the second.
     *
     * @param other the transaction to compare against, may be null
     * @return true when both describe the same event
     */
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

    /** Trimmed free text, with a blank treated as absent so that {@code null} and {@code ""} agree. */
    private static String canonical(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trimmed and upper-cased code, matching how the rules normalise currency and category. */
    private static String code(String value) {
        String trimmed = canonical(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
