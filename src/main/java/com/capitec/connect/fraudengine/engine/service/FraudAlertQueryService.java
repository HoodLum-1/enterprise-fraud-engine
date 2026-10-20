package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.domain.model.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FraudAlertQueryService {

    private final FraudAlertRepository fraudAlertRepository;
    private final FraudAlertMapper fraudAlertMapper;

    public Page<FraudAlertResponseDto> getAlertsByAccountId(
            String accountId, Severity severity, String ruleName, Instant from, Instant to, Pageable pageable) {
        log.info("Querying fraud alerts for account: {}", accountId);
        Specification<FraudAlertEntity> criteria = (root, query, builder) ->
            builder.equal(root.get("accountId"), accountId);
        if (severity != null) {
            criteria = criteria.and((root, query, builder) -> builder.equal(root.get("severity"), severity));
        }
        if (ruleName != null && !ruleName.isBlank()) {
            String normalizedRuleName = ruleName.trim().toLowerCase(java.util.Locale.ROOT);
            criteria = criteria.and((root, query, builder) ->
                builder.equal(builder.lower(root.get("ruleName")), normalizedRuleName));
        }
        if (from != null) {
            criteria = criteria.and((root, query, builder) -> builder.greaterThanOrEqualTo(root.get("flaggedAt"), from));
        }
        if (to != null) {
            criteria = criteria.and((root, query, builder) -> builder.lessThanOrEqualTo(root.get("flaggedAt"), to));
        }
        return fraudAlertRepository.findAll(criteria, pageable)
            .map(fraudAlertMapper::toResponseDto);
    }
}
