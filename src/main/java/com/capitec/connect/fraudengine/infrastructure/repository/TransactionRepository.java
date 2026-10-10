package com.capitec.connect.fraudengine.infrastructure.repository;

import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findByAccountIdAndTimestampAfterOrderByTimestampDesc(String accountId, Instant timestamp);
}
