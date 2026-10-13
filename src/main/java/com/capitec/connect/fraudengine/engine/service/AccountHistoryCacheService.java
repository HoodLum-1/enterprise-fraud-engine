package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.cache.CacheConfiguration;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dedicated cache service for account transaction history.
 * Separated from FraudEvaluationService to avoid Spring AOP self-invocation issues
 * when calling @Cacheable/@CacheEvict methods internally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountHistoryCacheService {

    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    /**
     * Retrieves account history with Redis caching for sub-50ms lookups.
     * Uses a time-bucketed cache key (5-minute windows) to balance cache hit rate with data freshness.
     *
     * @param accountId the account ID
     * @param historyCutoff the cutoff timestamp
     * @return list of historical transactions
     */
    @Cacheable(value = CacheConfiguration.ACCOUNT_HISTORY_CACHE,
        key = "#accountId + ':' + (#historyCutoff.toEpochMilli() / 300000)",
        unless = "#result.isEmpty()")
    public List<Transaction> getCachedAccountHistory(String accountId, Instant historyCutoff) {
        log.debug("Cache miss - fetching account history from database for account: {}", accountId);
        List<TransactionEntity> historyEntities =
            transactionRepository.findByAccountIdAndTimestampAfterOrderByTimestampDesc(accountId, historyCutoff);
        return historyEntities.stream()
            .map(entity -> transactionMapper.toDomain(entity))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Evicts account history cache when a new transaction is added.
     * Uses the same time-bucketed key as getCachedAccountHistory.
     */
    @CacheEvict(value = CacheConfiguration.ACCOUNT_HISTORY_CACHE,
        key = "#accountId + ':' + (#historyCutoff.toEpochMilli() / 300000)")
    public void evictAccountHistoryCache(String accountId, Instant historyCutoff) {
        log.debug("Evicted account history cache for account: {} bucket: {}", accountId, historyCutoff.toEpochMilli() / 300000);
    }
}