package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FraudEvaluationService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudEngine fraudEngine;
    private final TransactionMapper transactionMapper;
    private final FraudAlertMapper fraudAlertMapper;
    private final FraudEngineProperties engineProperties;

    public EvaluationSummary evaluate(TransactionRequestDto dto) {
        log.info("Processing transaction evaluation for id: {}, accountId: {}", dto.id(), dto.accountId());

        if (transactionRepository.existsById(dto.id())) {
            log.warn("Duplicate transaction detected with id: {}", dto.id());
            throw new DuplicateTransactionException(dto.id());
        }

        Transaction transaction = transactionMapper.toDomain(dto);
        TransactionEntity transactionEntity = transactionMapper.toEntity(transaction);
        transactionRepository.save(transactionEntity);

        Instant historyCutoff = Instant.now().minus(engineProperties.getEvaluation().getHistoryWindow());
        List<TransactionEntity> historyEntities = transactionRepository
            .findByAccountIdAndTimestampAfterOrderByTimestampDesc(dto.accountId(), historyCutoff);

        List<Transaction> accountHistory = historyEntities.stream()
            .filter(entity -> !entity.getId().equals(transaction.id()))
            .map(transactionMapper::toDomain)
            .toList();

        EvaluationSummary summary = fraudEngine.evaluate(transaction, accountHistory);

        if (summary.alerts() != null && !summary.alerts().isEmpty()) {
            List<FraudAlertEntity> alertEntities = summary.alerts().stream()
                .map(fraudAlertMapper::toEntity)
                .toList();
            fraudAlertRepository.saveAll(alertEntities);
            log.info("Persisted {} fraud alert(s) for transaction id: {}", alertEntities.size(), dto.id());
        }

        return summary;
    }
}
