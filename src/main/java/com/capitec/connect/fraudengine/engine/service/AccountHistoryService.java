
package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountHistoryService {

    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    public List<Transaction> findHistory(
            String accountId,
            Instant fromInclusive,
            Instant toInclusive) {

        List<Transaction> history = transactionRepository
                .findByAccountIdAndTimestampBetweenOrderByTimestampDesc(
                        accountId, fromInclusive, toInclusive)
                .stream()
                .map(transactionMapper::toDomain)
                .toList();

        log.info(
                "VELOCITY_DIAGNOSTIC history: accountId={}, count={}, from={}, to={}, timestamps={}",
                accountId,
                history.size(),
                fromInclusive,
                toInclusive,
                history.stream().map(Transaction::timestamp).toList()
        );

        return history;
    }
}
