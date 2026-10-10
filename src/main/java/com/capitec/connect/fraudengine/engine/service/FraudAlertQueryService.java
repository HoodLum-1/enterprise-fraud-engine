package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FraudAlertQueryService {

    private final FraudAlertRepository fraudAlertRepository;
    private final FraudAlertMapper fraudAlertMapper;

    public List<FraudAlertResponseDto> getAlertsByAccountId(String accountId) {
        log.info("Querying fraud alerts for account: {}", accountId);
        List<FraudAlertEntity> alerts = fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(accountId);
        return alerts.stream()
            .map(fraudAlertMapper::toResponseDto)
            .toList();
    }
}
