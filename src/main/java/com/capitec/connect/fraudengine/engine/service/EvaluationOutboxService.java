package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.messaging.FraudEventProducer;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvaluationOutboxService {

    private final EvaluationOutboxRepository outboxRepository;
    private final FraudEventProducer fraudEventProducer;
    private final ObjectMapper objectMapper;

    public void enqueueEvaluation(Transaction transaction, EvaluationSummary summary) {
        try {
            String payload = objectMapper.writeValueAsString(fraudEventProducer.buildEvent(transaction, summary));
            outboxRepository.save(EvaluationOutboxEntity.builder()
                .id(UUID.randomUUID())
                .eventKey(transaction.accountId())
                .eventType("TRANSACTION_EVALUATED")
                .payload(payload)
                .createdAt(Instant.now())
                .attempts(0)
                .build());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize transaction evaluation event", ex);
        }
    }
}
