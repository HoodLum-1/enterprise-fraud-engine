package com.capitec.connect.fraudengine.infrastructure.repository;

import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.domain.model.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlertEntity, UUID>,
    JpaSpecificationExecutor<FraudAlertEntity> {
    List<FraudAlertEntity> findByTransactionIdOrderByFlaggedAtDesc(UUID transactionId);
    Page<FraudAlertEntity> findByAccountIdOrderByFlaggedAtDesc(String accountId, Pageable pageable);
}
