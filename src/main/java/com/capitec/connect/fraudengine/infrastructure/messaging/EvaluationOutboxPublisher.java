package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "fraud.engine.messaging", name = "outbox-publisher-enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationOutboxPublisher {

    private final EvaluationOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${fraud.engine.messaging.evaluation-topic:fraud-alerts-topic}")
    private String evaluationTopic;
    @Value("${fraud.engine.messaging.account-actions-topic:account-actions-topic}")
    private String accountActionsTopic;

    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${fraud.engine.messaging.outbox-poll-delay-ms:1000}")
    public void publishPendingEvents() {
        List<EvaluationOutboxEntity> pending = outboxRepository.lockPending(50);
        for (EvaluationOutboxEntity row : pending) {
            publish(row);
        }
    }

    private void publish(EvaluationOutboxEntity row) {
        try {
            Object event;
            String topic;
            if ("TRANSACTION_EVALUATED".equals(row.getEventType())) {
                event = objectMapper.readValue(row.getPayload(), TransactionEvaluatedEvent.class);
                topic = evaluationTopic;
            } else if ("ACCOUNT_FREEZE".equals(row.getEventType())) {
                event = objectMapper.readValue(row.getPayload(), AccountActionEvent.class);
                topic = accountActionsTopic;
            } else {
                throw new IllegalArgumentException("Unsupported outbox event type: " + row.getEventType());
            }
            kafkaTemplate.send(topic, row.getEventKey(), event).get(10, TimeUnit.SECONDS);
            row.setPublishedAt(Instant.now());
            row.setNextAttemptAt(null);
            row.setLastError(null);
        } catch (Exception ex) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            long retryDelaySeconds = Math.min(300L, 1L << Math.min(attempts, 8));
            row.setNextAttemptAt(Instant.now().plusSeconds(retryDelaySeconds));
            row.setLastError(ex.getMessage());
            log.error("Failed to publish outbox event {} after {} attempts", row.getId(), row.getAttempts(), ex);
        }
    }
}
