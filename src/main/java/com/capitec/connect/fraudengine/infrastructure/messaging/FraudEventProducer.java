package com.capitec.connect.fraudengine.infrastructure.messaging;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps an evaluation result to the event persisted in the transactional outbox.
 */
@Service
public class FraudEventProducer {

    public TransactionEvaluatedEvent buildEvent(Transaction transaction, EvaluationSummary summary) {
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