package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TransactionMapperTest {

    private TransactionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TransactionMapper();
    }

    @Test
    void shouldMapEntityToDomain() {
        Instant now = Instant.now();
        TransactionEntity entity = FraudTestDataFactory.transactionEntity()
            .accountId("ACC-100")
            .amount(new java.math.BigDecimal("1250.75"))
            .category("ENTERTAINMENT")
            .merchantName("Cinema")
            .location("Johannesburg")
            .timestamp(now)
            .createdAt(now)
            .build();

        Transaction domain = mapper.toDomain(entity);

        assertNotNull(domain);
        assertEquals(entity.getId(), domain.id());
        assertEquals("ACC-100", domain.accountId());
        assertEquals(new BigDecimal("1250.75"), domain.amount());
        assertEquals("ZAR", domain.currency());
        assertEquals("ENTERTAINMENT", domain.category());
        assertEquals("Cinema", domain.merchantName());
        assertEquals("Johannesburg", domain.location());
        assertEquals(now, domain.timestamp());
    }

    @Test
    void shouldMapDomainToEntity() {
        Instant now = Instant.now();
        Transaction domain = FraudTestDataFactory.transaction()
            .accountId("ACC-100")
            .amount(new java.math.BigDecimal("1250.75"))
            .category("ENTERTAINMENT")
            .merchantName("Cinema")
            .location("Johannesburg")
            .timestamp(now)
            .build();

        TransactionEntity entity = mapper.toEntity(domain);

        assertNotNull(entity);
        assertEquals(domain.id(), entity.getId());
        assertEquals("ACC-100", entity.getAccountId());
        assertEquals(new BigDecimal("1250.75"), entity.getAmount());
        assertEquals("ZAR", entity.getCurrency());
        assertEquals("ENTERTAINMENT", entity.getCategory());
        assertEquals("Cinema", entity.getMerchantName());
        assertEquals("Johannesburg", entity.getLocation());
        assertEquals(now, entity.getTimestamp());
    }

    @Test
    void shouldReturnNullWhenMappingNull() {
        assertNull(mapper.toDomain((TransactionRequestDto) null));
        assertNull(mapper.toEntity(null));
    }
}
