package com.capitec.connect.fraudengine.engine;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.rule.FraudRule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Getter
@Service
@RequiredArgsConstructor
public class FraudEngine {

    private final List<FraudRule> rules;

    public EvaluationSummary evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
        log.debug("Evaluating transaction: {} with history count: {}", currentTransaction, accountHistory != null ? accountHistory.size() : 0);
        List<RuleEvaluationResult> triggeredResults = new ArrayList<>();
        List<FraudAlert> alerts = new ArrayList<>();
        int cumulativeRiskScore = 0;

        for (FraudRule rule : rules) {
            RuleEvaluationResult result = rule.evaluate(currentTransaction, accountHistory);
            if (result != null && result.isTriggered()) {
                triggeredResults.add(result);
                cumulativeRiskScore += result.riskScore();

                UUID txId = currentTransaction != null ? currentTransaction.id() : null;
                String accId = currentTransaction != null ? currentTransaction.accountId() : null;

                FraudAlert alert = new FraudAlert(
                    UUID.randomUUID(),
                    txId,
                    accId,
                    result.ruleName(),
                    result.riskScore(),
                    result.severity(),
                    result.reason(),
                    Instant.now()
                );
                alerts.add(alert);
            }
        }

        Severity overallSeverity = determineOverallSeverity(cumulativeRiskScore, triggeredResults);

        UUID transactionId = currentTransaction != null ? currentTransaction.id() : null;
        String accountId = currentTransaction != null ? currentTransaction.accountId() : null;

        return new EvaluationSummary(
            transactionId,
            accountId,
            cumulativeRiskScore,
            overallSeverity,
            triggeredResults,
            alerts
        );
    }

    private Severity determineOverallSeverity(int cumulativeRiskScore, List<RuleEvaluationResult> triggeredResults) {
        if (triggeredResults.isEmpty() || cumulativeRiskScore == 0) {
            return Severity.LOW;
        }

        boolean hasCritical = triggeredResults.stream().anyMatch(r -> r.severity() == Severity.CRITICAL);
        if (hasCritical || cumulativeRiskScore >= 80) {
            return Severity.CRITICAL;
        }

        boolean hasHigh = triggeredResults.stream().anyMatch(r -> r.severity() == Severity.HIGH);
        if (hasHigh || cumulativeRiskScore >= 50) {
            return Severity.HIGH;
        }

        boolean hasMedium = triggeredResults.stream().anyMatch(r -> r.severity() == Severity.MEDIUM);
        if (hasMedium || cumulativeRiskScore >= 25) {
            return Severity.MEDIUM;
        }

        return Severity.LOW;
    }
}
