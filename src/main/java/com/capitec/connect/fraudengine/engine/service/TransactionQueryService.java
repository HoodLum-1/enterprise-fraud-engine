package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudAlertMapper fraudAlertMapper;

    public TransactionDetailsResponseDto getDetails(UUID transactionId) {
        TransactionEntity entity = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<FraudAlertResponseDto> alerts = fraudAlertRepository
            .findByTransactionIdOrderByFlaggedAtDesc(transactionId)
            .stream()
            .map(fraudAlertMapper::toResponseDto)
            .toList();
        int riskScore = alerts.stream().mapToInt(FraudAlertResponseDto::riskScore).sum();
        Severity severity = determineSeverity(riskScore, alerts);
        return new TransactionDetailsResponseDto(
            entity.getId(), entity.getAccountId(), entity.getAmount(), entity.getCurrency(),
            entity.getCategory(), entity.getMerchantName(), entity.getLocation(), entity.getTimestamp(),
            !alerts.isEmpty(), riskScore, severity, alerts
        );
    }

    private Severity determineSeverity(int riskScore, List<FraudAlertResponseDto> alerts) {
        if (alerts.stream().anyMatch(alert -> alert.severity() == Severity.CRITICAL) || riskScore >= 80) {
            return Severity.CRITICAL;
        }
        if (alerts.stream().anyMatch(alert -> alert.severity() == Severity.HIGH) || riskScore >= 50) {
            return Severity.HIGH;
        }
        if (alerts.stream().anyMatch(alert -> alert.severity() == Severity.MEDIUM) || riskScore >= 25) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }
}
