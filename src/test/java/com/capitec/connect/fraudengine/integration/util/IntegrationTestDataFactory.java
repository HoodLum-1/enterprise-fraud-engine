package com.capitec.connect.fraudengine.integration.util;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Test data factory for integration tests.
 * Provides pre-configured {@link TransactionRequestDto} builders with realistic defaults
 * and specialized helpers for edge-case scenarios.
 */
public final class IntegrationTestDataFactory {

    // Integration test specific defaults (separate from unit test factory)
    private static final String DEFAULT_ACCOUNT_ID_PREFIX = "ACC-INT-";
    private static final String DEFAULT_CURRENCY = "ZAR";
    private static final String DEFAULT_CATEGORY = "GROCERIES";
    private static final String DEFAULT_MERCHANT_NAME = "Test Merchant";
    private static final String DEFAULT_LOCATION = "Johannesburg";

    private IntegrationTestDataFactory() {
    }

    // ===== Standard Builders =====

    /**
     * Creates a new builder for a valid transaction request with integration-test defaults.
     * Uses a fixed timestamp in the past to avoid {@code @PastOrPresent} validation issues.
     */
    public static TransactionRequestBuilder validTransaction() {
        return new TransactionRequestBuilder()
            .id(UUID.randomUUID())
            .accountId(DEFAULT_ACCOUNT_ID_PREFIX + "001")
            .amount(new BigDecimal("150.00"))
            .currency(DEFAULT_CURRENCY)
            .category(DEFAULT_CATEGORY)
            .merchantName(DEFAULT_MERCHANT_NAME)
            .location(DEFAULT_LOCATION)
            .timestamp(Instant.now().minusSeconds(60)); // 1 minute ago - safely in the past
    }

    /**
     * Creates a new builder for a valid transaction with a specific account ID.
     */
    public static TransactionRequestBuilder validTransaction(String accountId) {
        return validTransaction().accountId(accountId);
    }

    /**
     * Creates a new builder for a clean (low-risk) transaction.
     * Amount is well below the high-amount threshold (R50,000).
     */
    public static TransactionRequestBuilder cleanTransaction() {
        return validTransaction().amount(new BigDecimal("150.00"));
    }

    /**
     * Creates a new builder for a clean transaction with a specific account ID.
     */
    public static TransactionRequestBuilder cleanTransaction(String accountId) {
        return cleanTransaction().accountId(accountId);
    }

    // ===== Edge-Case Helpers =====

    /**
     * Creates a transaction request that will trigger the {@code HighAmountRule}.
     * Amount exceeds the configured threshold of R50,000.
     */
    public static TransactionRequestBuilder highAmountTransaction() {
        return validTransaction()
            .amount(new BigDecimal("75000.00"))
            .category("ELECTRONICS")
            .merchantName("Luxury Store")
            .location("Cape Town");
    }

    /**
     * Creates a high-amount transaction with a specific account ID.
     */
    public static TransactionRequestBuilder highAmountTransaction(String accountId) {
        return highAmountTransaction().accountId(accountId);
    }

    /**
     * Creates a transaction request with a negative amount (validation error).
     */
    public static TransactionRequestBuilder negativeAmountTransaction() {
        return validTransaction()
            .amount(new BigDecimal("-50.00"));
    }

    /**
     * Creates a transaction request with an invalid currency code (validation error).
     */
    public static TransactionRequestBuilder invalidCurrencyTransaction() {
        return validTransaction()
            .currency("INVALID");
    }

    /**
     * Creates a transaction request with missing/invalid required fields (validation error).
     */
    public static TransactionRequestBuilder invalidPayloadTransaction() {
        return new TransactionRequestBuilder()
            .id(null)
            .accountId("")
            .amount(new BigDecimal("-50.00"))
            .currency("INVALID")
            .category("")
            .merchantName("Store")
            .location("Johannesburg")
            .timestamp(null);
    }

    /**
     * Creates a transaction request suitable for duplicate ID testing.
     * Returns a builder with a pre-generated UUID that can be used for both
     * the initial request and the duplicate.
     */
    public static TransactionRequestBuilder duplicateTransaction() {
        return duplicateTransaction(UUID.randomUUID());
    }

    /**
     * Creates a duplicate transaction builder with a specific ID.
     * Use this when you need to control the exact UUID for duplicate testing.
     */
    public static TransactionRequestBuilder duplicateTransaction(UUID fixedId) {
        return validTransaction()
            .id(fixedId)
            .amount(new BigDecimal("100.00"));
    }

    /**
     * Creates a transaction request suitable for velocity rule testing.
     * Uses a base timestamp in the past with configurable offset.
     */
    public static TransactionRequestBuilder velocityTransaction(String accountId, Instant baseTime, int offsetSeconds) {
        return validTransaction(accountId)
            .amount(new BigDecimal("50.00"))
            .timestamp(baseTime.plusSeconds(offsetSeconds));
    }

    /**
     * Creates a series of velocity transactions within the 2-minute window.
     * @param accountId the account ID for all transactions
     * @param baseTime the base timestamp (should be in the past)
     * @param count number of transactions to create
     * @param intervalSeconds seconds between each transaction
     * @return array of TransactionRequestDto
     */
    public static TransactionRequestDto[] velocitySeries(String accountId, Instant baseTime, int count, int intervalSeconds) {
        TransactionRequestDto[] requests = new TransactionRequestDto[count];
        for (int i = 0; i < count; i++) {
            requests[i] = velocityTransaction(accountId, baseTime, i * intervalSeconds).build();
        }
        return requests;
    }

    // ===== Builder Class =====

    /**
     * Fluent builder for {@link TransactionRequestDto}.
     */
    public static final class TransactionRequestBuilder {
        private UUID id;
        private String accountId;
        private BigDecimal amount;
        private String currency;
        private String category;
        private String merchantName;
        private String location;
        private Instant timestamp;

        private TransactionRequestBuilder() {
        }

        public TransactionRequestBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public TransactionRequestBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public TransactionRequestBuilder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public TransactionRequestBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public TransactionRequestBuilder category(String category) {
            this.category = category;
            return this;
        }

        public TransactionRequestBuilder merchantName(String merchantName) {
            this.merchantName = merchantName;
            return this;
        }

        public TransactionRequestBuilder location(String location) {
            this.location = location;
            return this;
        }

        public TransactionRequestBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the timestamp to a fixed instant in the past (1 minute ago).
         * Useful to avoid {@code @PastOrPresent} validation issues when tests run at different times.
         */
        public TransactionRequestBuilder withPastTimestamp() {
            this.timestamp = Instant.now().minusSeconds(60);
            return this;
        }

        public TransactionRequestDto build() {
            return new TransactionRequestDto(
                id, accountId, amount, currency, category, merchantName, location, timestamp
            );
        }
    }
}