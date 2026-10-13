package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudCaseEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudCaseMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudAlertConsumer {

    private static final String ACCOUNT_ACTIONS_TOPIC = "account-actions-topic";

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseMapper fraudCaseMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    @KafkaListener(
            topics = "fraud-alerts-topic",
            groupId = "fraud-alert-consumer-group",
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

        // --- 1. Fraud Case Management: Persist high-risk events (PENDING_REVIEW) ---
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
            log.info("Persisted new fraud case ID {} for transaction {} with severity {} and risk score {}",
                    saved.getId(), event.getTransactionId(), event.getOverallSeverity(), event.getTotalRiskScore());
        }

        // --- 2. Automated Mitigation: CRITICAL severity or riskScore >= 80 ---
        boolean isCriticalOrHigh =
                event.getOverallSeverity() == Severity.CRITICAL
                        || event.getTotalRiskScore() >= 80;

        if (isCriticalOrHigh) {
            log.warn("AUTOMATED MITIGATION: Transaction {} from account {} is CRITICAL/HIGH risk "
                            + "(severity={}, riskScore={}) - initiating account freeze",
                    event.getTransactionId(), event.getAccountId(),
                    event.getOverallSeverity(), event.getTotalRiskScore());

            String freezeEvent = String.format(
                    "{\"eventId\":\"%s\",\"eventType\":\"ACCOUNT_FREEZE\",\"transactionId\":\"%s\",\"accountId\":\"%s\",\"reason\":\"Critical/high risk fraud detected\"}",
                    UUID.randomUUID(), event.getTransactionId(), event.getAccountId());

            kafkaTemplate.send(ACCOUNT_ACTIONS_TOPIC, event.getAccountId(), freezeEvent);
            log.info("Published ACCOUNT_FREEZE command to topic {} for account {}", ACCOUNT_ACTIONS_TOPIC, event.getAccountId());
        }

        // --- 3. Customer Notification Mock ---
        log.info("MOCK NOTIFICATION: Cardholder alert triggered asynchronously for transaction {} from account {} - "
                        + "Your transaction has been flagged for security review. Reference ID: {}",
                event.getTransactionId(), event.getAccountId(), event.getTransactionId());
    }
}