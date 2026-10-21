package com.capitec.connect.fraudengine.infrastructure.repository;

import com.capitec.connect.fraudengine.infrastructure.entity.FreezeAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FreezeAuditRepository extends JpaRepository<FreezeAuditEntity, UUID> {
    boolean existsByTransactionId(UUID transactionId);
}
