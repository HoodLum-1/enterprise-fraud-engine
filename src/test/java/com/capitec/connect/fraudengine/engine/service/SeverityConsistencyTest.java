package com.capitec.connect.fraudengine.engine.service;

import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.engine.rule.FraudRule;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeverityConsistencyTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FraudAlertRepository fraudAlertRepository;

    @Test
    void theEngineAndTheQueryPathNeverDisagreeOnTheSeverity() {
        assertBothPathsAgree(List.of());
        assertBothPathsAgree(List.of(rule(24, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(25, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(49, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(50, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(79, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(80, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(0, Severity.MEDIUM)));
        assertBothPathsAgree(List.of(rule(0, Severity.HIGH)));
        assertBothPathsAgree(List.of(rule(0, Severity.CRITICAL)));
        assertBothPathsAgree(List.of(rule(30, Severity.MEDIUM), rule(60, Severity.CRITICAL)));
        assertBothPathsAgree(List.of(rule(40, Severity.MEDIUM), rule(10, Severity.LOW)));
        assertBothPathsAgree(List.of(rule(45, Severity.HIGH), rule(45, Severity.MEDIUM)));
        assertBothPathsAgree(List.of(rule(12, Severity.LOW), rule(13, Severity.LOW)));
    }

    private void assertBothPathsAgree(List<RuleEvaluationResult> results) {
        UUID transactionId = UUID.randomUUID();
        String accountId = "ACC-CONSISTENCY";

        EvaluationSummary evaluated = new FraudEngine(
            results.stream().map(StubRule::new).map(FraudRule.class::cast).toList())
            .evaluate(transaction(transactionId, accountId), List.of());

        List<FraudAlertEntity> stored = evaluated.alerts().stream()
            .map(alert -> FraudAlertEntity.builder()
                .id(alert.id())
                .transactionId(alert.transactionId())
                .accountId(alert.accountId())
                .ruleName(alert.ruleName())
                .riskScore(alert.riskScore())
                .severity(alert.severity())
                .reason(alert.reason())
                .flaggedAt(alert.flaggedAt())
                .build())
            .toList();

        when(transactionRepository.findById(transactionId))
            .thenReturn(Optional.of(transactionEntity(transactionId, accountId)));
        when(fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(transactionId))
            .thenReturn(stored);

        TransactionDetailsResponseDto readBack = new TransactionQueryService(
            transactionRepository, fraudAlertRepository, new FraudAlertMapper())
            .getDetails(transactionId);

        String description = describe(results);
        assertEquals(evaluated.totalRiskScore(), readBack.totalRiskScore(),
            "the risk score changed between being written and read back: " + description);
        assertEquals(evaluated.overallSeverity(), readBack.overallSeverity(),
            "the severity changed between being written and read back: " + description);
        assertEquals(evaluated.isFraudulent(), readBack.isFraudulent(),
            "the verdict changed between being written and read back: " + description);
        assertEquals(evaluated.alerts().size(), readBack.alerts().size(),
            "an alert was lost between being written and read back: " + description);
    }

    private RuleEvaluationResult rule(int riskScore, Severity severity) {
        return new RuleEvaluationResult("ConsistencyRule", true, riskScore, severity,
            "Triggered for the consistency test");
    }

    private Transaction transaction(UUID id, String accountId) {
        return new Transaction(id, accountId, new BigDecimal("100.00"), "ZAR", "GROCERIES",
            "Market", "Cape Town", Instant.now());
    }

    private TransactionEntity transactionEntity(UUID id, String accountId) {
        return TransactionEntity.builder()
            .id(id)
            .accountId(accountId)
            .amount(new BigDecimal("100.00"))
            .currency("ZAR")
            .category("GROCERIES")
            .merchantName("Market")
            .location("Cape Town")
            .timestamp(Instant.now())
            .build();
    }

    private String describe(List<RuleEvaluationResult> results) {
        return results.isEmpty() ? "no findings"
            : results.stream().map(r -> r.riskScore() + "pts/" + r.severity()).toList().toString();
    }

    private record StubRule(RuleEvaluationResult result) implements FraudRule {

        @Override
        public String getRuleName() {
            return result.ruleName();
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory) {
            return result;
        }
    }
}
