package com.capitec.connect.fraudengine.engine.rule;

import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import java.util.List;

public interface FraudRule {
    String getRuleName();
    int getOrder();
    RuleEvaluationResult evaluate(Transaction currentTransaction, List<Transaction> accountHistory);
}
