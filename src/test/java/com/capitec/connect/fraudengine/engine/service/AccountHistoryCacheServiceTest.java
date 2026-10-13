package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.cache.CacheConfiguration;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountHistoryCacheService}.
 * <p>
 * The service is a thin {@code @Cacheable}/{@code @CacheEvict} facade, so besides the repository
 * delegation these tests pin down the two invariants the Redis integration depends on: the returned
 * history must be JSON-round-trippable (mutable {@code ArrayList}, not {@code List.of()}) and
 * {@link Instant} fields must survive the encoding used by {@link CacheConfiguration#cacheValueSerializer()}.
 */
@ExtendWith(MockitoExtension.class)
class AccountHistoryCacheServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private AccountHistoryCacheService accountHistoryCacheService;

    private final TransactionMapper transactionMapper = new TransactionMapper();
    private final GenericJackson2JsonRedisSerializer cacheSerializer =
        new CacheConfiguration().cacheValueSerializer();

    private final String accountId = "ACC-1001";
    private final Instant cutoff = Instant.parse("2026-10-13T10:00:00Z");

    private TransactionEntity entity(String merchant, Instant timestamp) {
        return TransactionEntity.builder()
            .id(UUID.randomUUID())
            .accountId(accountId)
            .amount(new BigDecimal("150.00"))
            .currency("ZAR")
            .category("GROCERIES")
            .merchantName(merchant)
            .location("Johannesburg")
            .timestamp(timestamp)
            .build();
    }

    @BeforeEach
    void setUp() {
        accountHistoryCacheService = new AccountHistoryCacheService(transactionRepository, transactionMapper);
    }

    @Test
    void getCachedAccountHistory_delegatesToRepositoryWithinTheGivenWindow() {
        Instant timestamp = Instant.parse("2026-10-13T09:30:15.123456789Z");
        when(transactionRepository.findByAccountIdAndTimestampAfterOrderByTimestampDesc(eq(accountId), any(Instant.class)))
            .thenReturn(List.of(entity("Woolworths", timestamp)));

        List<Transaction> history = accountHistoryCacheService.getCachedAccountHistory(accountId, cutoff);

        assertEquals(1, history.size());
        assertEquals(accountId, history.getFirst().accountId());
        assertEquals("Woolworths", history.getFirst().merchantName());
        assertEquals(timestamp, history.getFirst().timestamp());
    }

    @Test
    void getCachedAccountHistory_resultSurvivesTheRedisJsonEncodingUsedByTheCache() {
        Instant exactNanos = Instant.parse("2026-10-13T09:30:15.123456789Z");
        when(transactionRepository.findByAccountIdAndTimestampAfterOrderByTimestampDesc(eq(accountId), any(Instant.class)))
            .thenReturn(List.of(entity("Woolworths", exactNanos), entity("Checkers", exactNanos.minusSeconds(60))));

        List<Transaction> history = accountHistoryCacheService.getCachedAccountHistory(accountId, cutoff);

        assertInstanceOf(java.util.ArrayList.class, history,
            "history must be a mutable ArrayList so the Redis JSON entry can be deserialized");

        byte[] encoded = cacheSerializer.serialize(history);
        Object decoded = cacheSerializer.deserialize(encoded);

        assertInstanceOf(List.class, decoded);
        List<?> entries = (List<?>) decoded;
        assertEquals(2, entries.size());

        Transaction first = (Transaction) entries.getFirst();
        assertEquals(history.getFirst().id(), first.id());
        assertEquals(history.getFirst().amount(), first.amount());
        assertEquals(exactNanos, first.timestamp(), "Instant must round-trip with nanosecond precision");
    }

    @Test
    void getCachedAccountHistory_returnsEmptyListWhenThereIsNoHistory() {
        when(transactionRepository.findByAccountIdAndTimestampAfterOrderByTimestampDesc(eq(accountId), any(Instant.class)))
            .thenReturn(List.of());

        List<Transaction> history = accountHistoryCacheService.getCachedAccountHistory(accountId, cutoff);

        assertTrue(history.isEmpty());
        // empty results are never cached (`unless = "#result.isEmpty()"`), so they must not be encoded
        assertDoesNotThrow(() -> cacheSerializer.serialize(history));
    }
}
