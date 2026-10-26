package com.capitec.connect.fraudengine.engine;

import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.SeverityAssessment;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.rule.FraudRule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Service
@RequiredArgsConstructor
public class FraudEngine {

    private final List<FraudRule> rules;

    public EvaluationSummary evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
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

        Severity overallSeverity = SeverityAssessment.assess(cumulativeRiskScore,
            triggeredResults.stream().map(RuleEvaluationResult::severity).toList());

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
}
