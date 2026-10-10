package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import org.springframework.stereotype.Component;

@Component
public class FraudAlertMapper {

    public FraudAlertResponseDto toResponseDto(FraudAlert domain) {
        if (domain == null) {
            return null;
        }
        return new FraudAlertResponseDto(
            domain.id(),
            domain.transactionId(),
            domain.accountId(),
            domain.ruleName(),
            domain.riskScore(),
            domain.severity(),
            domain.reason(),
            domain.flaggedAt()
        );
    }

    public FraudAlertResponseDto toResponseDto(FraudAlertEntity entity) {
        if (entity == null) {
            return null;
        }
        return new FraudAlertResponseDto(
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
