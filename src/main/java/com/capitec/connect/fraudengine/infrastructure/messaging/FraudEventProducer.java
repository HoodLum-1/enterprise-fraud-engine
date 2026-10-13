package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Kafka event producer for fraud evaluation events.
 * Asynchronously publishes TransactionEvaluatedEvent to the fraud-alerts-topic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudEventProducer {

    private static final String TOPIC = "fraud-alerts-topic";

    // Matches the KafkaTemplate<String, Object> bean defined in KafkaConsumerConfig
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes a transaction evaluation event to Kafka.
     * Runs asynchronously and does not block the evaluation flow.
     *
     * @param transaction the transaction that was evaluated
     * @param summary the evaluation summary with rule results and alerts
     */
    public void publishEvaluationEvent(Transaction transaction, EvaluationSummary summary) {
        TransactionEvaluatedEvent event = buildEvent(transaction, summary);

        CompletableFuture<SendResult<String, Object>> future =
            kafkaTemplate.send(TOPIC, transaction.accountId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish fraud evaluation event for transaction {}: {}",
                    transaction.id(), ex.getMessage(), ex);
            } else {
                log.debug("Published fraud evaluation event for transaction {} to topic {} partition {} offset {}",
                    transaction.id(), result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Builds the TransactionEvaluatedEvent from the transaction and evaluation summary.
     */
    private TransactionEvaluatedEvent buildEvent(Transaction transaction, EvaluationSummary summary) {
        List<TransactionEvaluatedEvent.RuleEvaluationInfo> triggeredRules = summary.triggeredRules() != null
            ? summary.triggeredRules().stream()
                .map(this::mapToRuleEvaluationInfo)
                .collect(Collectors.toList())
            : List.of();

        return TransactionEvaluatedEvent.builder()
            .eventId(java.util.UUID.randomUUID())
            .eventType("TRANSACTION_EVALUATED")
            .timestamp(java.time.Instant.now())
            .transactionId(transaction.id())
            .accountId(transaction.accountId())
            .amount(transaction.amount())
            .currency(transaction.currency())
            .category(transaction.category())
            .merchantName(transaction.merchantName())
            .location(transaction.location())
            .totalRiskScore(summary.totalRiskScore())
            .overallSeverity(summary.overallSeverity())
            .isFraudulent(summary.isFraudulent())
            .triggeredRules(triggeredRules)
            .alertsGenerated(summary.alerts() != null ? summary.alerts().size() : 0)
            .build();
    }

    private TransactionEvaluatedEvent.RuleEvaluationInfo mapToRuleEvaluationInfo(RuleEvaluationResult result) {
        return TransactionEvaluatedEvent.RuleEvaluationInfo.builder()
            .ruleName(result.ruleName())
            .triggered(result.isTriggered())
            .riskScore(result.riskScore())
            .severity(result.severity())
            .reason(result.reason())
            .build();
    }
}