package com.capitec.connect.fraudengine.infrastructure.mapper;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    public Transaction toDomain(TransactionRequestDto dto) {
        if (dto == null) {
            return null;
        }
        return new Transaction(
            dto.id(),
            dto.accountId(),
            dto.amount(),
            dto.currency(),
            dto.category(),
            dto.merchantName(),
            dto.location(),
            dto.timestamp()
        );
    }

    public Transaction toDomain(TransactionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Transaction(
            entity.getId(),
            entity.getAccountId(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getCategory(),
            entity.getMerchantName(),
            entity.getLocation(),
            entity.getTimestamp()
        );
    }

    public TransactionEntity toEntity(Transaction domain) {
        if (domain == null) {
            return null;
        }
        return TransactionEntity.builder()
            .id(domain.id())
            .accountId(domain.accountId())
            .amount(domain.amount())
            .currency(domain.currency())
            .category(domain.category())
            .merchantName(domain.merchantName())
            .location(domain.location())
            .timestamp(domain.timestamp())
            .build();
    }
}
