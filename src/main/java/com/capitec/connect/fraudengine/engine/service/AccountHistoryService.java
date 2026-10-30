package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountHistoryService {

    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    public List<Transaction> findHistory(String accountId, Instant fromInclusive, Instant toInclusive) {
        return transactionRepository
            .findByAccountIdAndTimestampBetweenOrderByTimestampDesc(accountId, fromInclusive, toInclusive)
            .stream()
            .map(transactionMapper::toDomain)
            .toList();
    }
}
