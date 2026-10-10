package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import org.springframework.stereotype.Component;

@Component
public class FraudAlertMapper {

    public FraudAlert toDomain(FraudAlertEntity entity) {
        if (entity == null) {
            return null;
        }
        return new FraudAlert(
            entity.getId(),
            entity.getTransactionId(),
            entity.getAccountId(),
            entity.getRuleName(),
            entity.getRiskScore(),
            entity.getSeverity(),
            entity.getReason(),
            entity.getFlaggedAt()
        );
    }

    public FraudAlertEntity toEntity(FraudAlert domain) {
        if (domain == null) {
            return null;
        }
        return FraudAlertEntity.builder()
            .id(domain.id())
            .transactionId(domain.transactionId())
            .accountId(domain.accountId())
            .ruleName(domain.ruleName())
            .riskScore(domain.riskScore())
            .severity(domain.severity())
            .reason(domain.reason())
            .flaggedAt(domain.flaggedAt())
            .build();
    }
}
