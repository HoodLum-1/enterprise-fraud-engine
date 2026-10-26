package com.capitec.connect.fraudengine.domain.model;

import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    @Test
    void shouldCreateValidTransaction() {
        Instant now = Instant.now();
        Transaction tx = FraudTestDataFactory.transaction()
            .accountId("ACC-1")
            .amount(new BigDecimal("100.50"))
            .category("RETAIL")
            .merchantName("Supermarket")
            .location("Cape Town")
            .timestamp(now)
            .build();

        assertNotNull(tx.id());
        assertEquals("ACC-1", tx.accountId());
        assertEquals(new BigDecimal("100.50"), tx.amount());
        assertEquals("ZAR", tx.currency());
        assertEquals("RETAIL", tx.category());
        assertEquals("Supermarket", tx.merchantName());
        assertEquals("Cape Town", tx.location());
        assertEquals(now, tx.timestamp());
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        NullPointerException ex = assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.transaction().id(null).build()
        );
        assertTrue(ex.getMessage().contains("Transaction ID must not be null"));
    }

    @Test
    void shouldThrowExceptionWhenAccountIdIsBlank() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.transaction().accountId("   ").build()
        );
        assertTrue(ex.getMessage().contains("Account ID must not be null or blank"));
    }

    @Test
    void shouldThrowExceptionWhenAmountIsZeroOrNegative() {
        IllegalArgumentException zeroEx = assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.transaction().amount(BigDecimal.ZERO).build()
        );
        assertTrue(zeroEx.getMessage().contains("Amount must be positive"));

        IllegalArgumentException negEx = assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.transaction().amount(new BigDecimal("-10.00")).build()
        );
        assertTrue(negEx.getMessage().contains("Amount must be positive"));
    }

    @Test
    void shouldThrowExceptionWhenCurrencyOrCategoryIsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.transaction().currency("").build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            FraudTestDataFactory.transaction().category("  ").build()
        );
    }

    @Test
    void shouldThrowExceptionWhenTimestampIsNull() {
        assertThrows(NullPointerException.class, () ->
            FraudTestDataFactory.transaction().timestamp(null).build()
        );
    }

    @Test
    void theSameEventSpelledDifferentlyIsStillTheSameTransaction() {
        // These are the differences a retry produces without any intent behind them: the client
        // re-serialised the amount, upper-cased the category it had typed in lower case, or sent
        // an empty optional instead of omitting it. Refusing them would answer a harmless retry
        // with a conflict the caller cannot resolve.
        Transaction original = transaction();

        assertTrue(original.isSameTransactionAs(transaction()));
        assertTrue(original.isSameTransactionAs(builder().amount(new BigDecimal("100.5")).build()));
        assertTrue(original.isSameTransactionAs(builder().amount(new BigDecimal("100.500")).build()));
        assertTrue(original.isSameTransactionAs(builder().category("  retail ").build()));
        assertTrue(original.isSameTransactionAs(builder().currency("zar").build()));
        assertTrue(original.isSameTransactionAs(builder().merchantName("  Supermarket  ").build()));
        assertTrue(original.isSameTransactionAs(builder().location("  Cape Town ").build()));
    }

    @Test
    void aBlankMerchantNameAndAnAbsentOneAreTheSameTransaction() {
        // Both mean "the caller told us nothing", which is what the column holds either way.
        assertTrue(builder().merchantName("").build()
            .isSameTransactionAs(builder().merchantName(null).build()));
        assertTrue(builder().location("   ").build()
            .isSameTransactionAs(builder().location(null).build()));
    }

    @Test
    void anythingThatWouldDescribeADifferentEventIsNotTheSameTransaction() {
        Transaction original = transaction();

        assertFalse(original.isSameTransactionAs(null));
        assertFalse(original.isSameTransactionAs(builder().id(UUID.randomUUID()).build()));
        assertFalse(original.isSameTransactionAs(builder().accountId("ACC-2").build()));
        assertFalse(original.isSameTransactionAs(builder().accountId(" acc-1 ").build()));
        assertFalse(original.isSameTransactionAs(builder().amount(new BigDecimal("100.51")).build()));
        assertFalse(original.isSameTransactionAs(builder().amount(new BigDecimal("200.50")).build()));
        assertFalse(original.isSameTransactionAs(builder().currency("USD").build()));
        assertFalse(original.isSameTransactionAs(builder().category("TRAVEL").build()));
        assertFalse(original.isSameTransactionAs(builder().merchantName("Other Store").build()));
        assertFalse(original.isSameTransactionAs(builder().location("Durban").build()));
        assertFalse(original.isSameTransactionAs(
            builder().timestamp(original.timestamp().plusSeconds(1)).build()));
    }

    @Test
    void aDifferentEventTimeIsADifferentTransactionEvenWhenEverythingElseMatches() {
        // Two payments at different times are two payments. Collapsing them would drop the second
        // one and every alert it would have raised.
        Transaction first = transaction();
        Transaction second = builder().timestamp(first.timestamp().plusMillis(1)).build();

        assertFalse(first.isSameTransactionAs(second));
    }

    private Transaction transaction() {
        return builder().build();
    }

    private FraudTestDataFactory.TransactionBuilder builder() {
        return FraudTestDataFactory.transaction()
            .id(UUID.fromString("11111111-2222-3333-4444-555555555555"))
            .accountId("ACC-1")
            .amount(new BigDecimal("100.50"))
            .currency("ZAR")
            .category("RETAIL")
            .merchantName("Supermarket")
            .location("Cape Town")
            .timestamp(Instant.parse("2026-10-26T10:00:00Z"));
    }
}
