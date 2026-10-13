package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import org.springframework.stereotype.Component;

@Component
public class FraudCaseMapper {

    public FraudCase toDomain(FraudCaseEntity entity) {
        if (entity == null) {
            return null;
        }
        return new FraudCase(
                entity.getId(),
                entity.getTransactionId(),
                entity.getAccountId(),
                entity.getTotalRiskScore(),
                entity.getOverallSeverity(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public FraudCaseEntity toEntity(FraudCase domain) {
        if (domain == null) {
            return null;
        }
        return FraudCaseEntity.builder()
                .id(domain.id())
                .transactionId(domain.transactionId())
                .accountId(domain.accountId())
                .totalRiskScore(domain.totalRiskScore())
                .overallSeverity(domain.overallSeverity())
                .status(domain.status())
                .createdAt(domain.createdAt())
                .updatedAt(domain.updatedAt())
                .build();
    }
}