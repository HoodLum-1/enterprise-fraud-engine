package com.capitec.connect.fraudengine.domain.model;

import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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
}
