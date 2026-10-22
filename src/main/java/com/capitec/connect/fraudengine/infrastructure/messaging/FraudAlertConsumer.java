package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.OutboxStatus;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.FreezeAuditEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.FreezeAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudAlertConsumer {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseMapper fraudCaseMapper;
    private final FreezeAuditRepository freezeAuditRepository;
    private final EvaluationOutboxRepository outboxRepository;
    private final FraudEngineProperties engineProperties;
    private final ObjectMapper objectMapper;
    private final FraudMetrics fraudMetrics;

    @Transactional
    @KafkaListener(
            topics = "${fraud.engine.messaging.evaluation-topic:fraud-alerts-topic}",
            groupId = "${spring.kafka.consumer.fraud-alert-group-id:fraud-alert-consumer-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeTransactionEvaluatedEvent(
            @Payload TransactionEvaluatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {

        if (event == null) {
            log.warn("Received null TransactionEvaluatedEvent from topic: {}", topic);
            return;
        }

        log.info("Consumed fraud alert event for transaction {} from account {}",
                event.getTransactionId(), event.getAccountId());

        boolean isFraudulent = event.isFraudulent() || event.getAlertsGenerated() > 0
            || (event.getTriggeredRules() != null && event.getTriggeredRules().stream()
                .anyMatch(TransactionEvaluatedEvent.RuleEvaluationInfo::isTriggered));
        if (!isFraudulent) {
            log.debug("Ignoring clean evaluation event for transaction {}", event.getTransactionId());
            return;
        }

        Optional<FraudCaseEntity> existingCase = fraudCaseRepository.findByTransactionId(event.getTransactionId());
        if (existingCase.isPresent()) {
            log.debug("Fraud case already exists for transaction {} - skipping duplicate persist", event.getTransactionId());
        } else {
            FraudCase newCase = new FraudCase(
                    event.getTransactionId(),
                    event.getAccountId(),
                    event.getTotalRiskScore(),
                    event.getOverallSeverity()
            );
            FraudCaseEntity saved = fraudCaseRepository.save(fraudCaseMapper.toEntity(newCase));
            fraudMetrics.recordCaseCreated();
            log.info("Persisted new fraud case ID {} for transaction {} with severity {} and risk score {}",
                    saved.getId(), event.getTransactionId(), event.getOverallSeverity(), event.getTotalRiskScore());
        }

        FraudEngineProperties.FreezePolicy policy = engineProperties.getFreezePolicy();
        boolean freezeEligible = policy.isEnabled()
            && (event.getTotalRiskScore() >= policy.getMinimumRiskScore()
                || (event.getOverallSeverity() != null
                    && event.getOverallSeverity().ordinal() >= policy.getMinimumSeverity().ordinal()));
        fraudMetrics.recordFreezeDecision(freezeEligible);

        if (freezeEligible && !freezeAuditRepository.existsByTransactionId(event.getTransactionId())) {
            log.warn("AUTOMATED MITIGATION: Transaction {} from account {} is CRITICAL/HIGH risk "
                            + "(severity={}, riskScore={}) - initiating account freeze",
                    event.getTransactionId(), event.getAccountId(),
                    event.getOverallSeverity(), event.getTotalRiskScore());

            UUID eventId = event.getEventId() != null ? event.getEventId() : event.getTransactionId();
            String reason = "Critical/high risk fraud detected";
            Instant now = Instant.now();
            freezeAuditRepository.save(FreezeAuditEntity.builder()
                .id(UUID.randomUUID())
                .transactionId(event.getTransactionId())
                .eventId(eventId)
                .accountId(event.getAccountId())
                .totalRiskScore(event.getTotalRiskScore())
                .overallSeverity(event.getOverallSeverity())
                .reason(reason)
                .decidedAt(now)
                .build());
            AccountActionEvent action = AccountActionEvent.builder()
                .eventId(eventId)
                .eventType("ACCOUNT_FREEZE")
                .transactionId(event.getTransactionId())
                .accountId(event.getAccountId())
                .reason(reason)
                .timestamp(now)
                .build();
            try {
                outboxRepository.save(EvaluationOutboxEntity.builder()
                    .id(UUID.randomUUID())
                    .eventKey(event.getAccountId())
                    .eventType("ACCOUNT_FREEZE")
                    .payload(objectMapper.writeValueAsString(action))
                    .createdAt(now)
                    .attempts(0)
                    .status(OutboxStatus.PENDING)
                    .build());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Unable to serialize account freeze event", ex);
            }
        }

        // --- 3. Customer Notification Mock ---
        log.info("MOCK NOTIFICATION: Cardholder alert triggered asynchronously for transaction {} from account {} - "
                        + "Your transaction has been flagged for security review. Reference ID: {}",
                event.getTransactionId(), event.getAccountId(), event.getTransactionId());
    }
}