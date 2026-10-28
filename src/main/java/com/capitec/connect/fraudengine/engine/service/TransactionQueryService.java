package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.SeverityAssessment;
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

        Severity severity = SeverityAssessment.assess(riskScore,
            alerts.stream().map(FraudAlertResponseDto::severity).toList());
        return new TransactionDetailsResponseDto(
            entity.getId(), entity.getAccountId(), entity.getAmount(), entity.getCurrency(),
            entity.getCategory(), entity.getMerchantName(), entity.getLocation(), entity.getTimestamp(),
            !alerts.isEmpty(), riskScore, severity, alerts
        );
    }
}
