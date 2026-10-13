package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudCaseService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseMapper fraudCaseMapper;

    @Transactional(readOnly = true)
    public List<FraudCase> findAll() {
        return fraudCaseRepository.findAll().stream()
                .map(fraudCaseMapper::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public FraudCase findByTransactionId(UUID transactionId) {
        return fraudCaseRepository.findByTransactionId(transactionId)
                .map(fraudCaseMapper::toDomain)
                .orElse(null);
    }

    @Transactional
    public FraudCase createFromEvaluation(UUID transactionId, String accountId, int totalRiskScore, Severity overallSeverity) {
        FraudCase fraudCase = new FraudCase(transactionId, accountId, totalRiskScore, overallSeverity);
        FraudCaseEntity entity = fraudCaseMapper.toEntity(fraudCase);
        FraudCaseEntity saved = fraudCaseRepository.save(entity);
        log.info("Created fraud case ID {} for transaction {}", saved.getId(), transactionId);
        return fraudCaseMapper.toDomain(saved);
    }

    @Transactional
    public FraudCase updateStatus(UUID transactionId, String newStatus) {
        FraudCaseEntity entity = fraudCaseRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Fraud case not found for transaction: " + transactionId));
        entity.updateStatus(newStatus);
        FraudCaseEntity saved = fraudCaseRepository.save(entity);
        log.info("Updated fraud case ID {} status to {}", saved.getId(), newStatus);
        return fraudCaseMapper.toDomain(saved);
    }
}