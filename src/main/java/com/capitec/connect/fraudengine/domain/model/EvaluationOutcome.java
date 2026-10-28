package com.capitec.connect.fraudengine.domain.model;

import java.util.UUID;

public record EvaluationOutcome(EvaluationSummary summary, boolean created) {

    public EvaluationOutcome {
        if (summary == null) {
            throw new IllegalArgumentException("Evaluation summary must not be null");
        }
    }

    public static EvaluationOutcome created(EvaluationSummary summary) {
        return new EvaluationOutcome(summary, true);
    }

    public static EvaluationOutcome replayed(EvaluationSummary summary) {
        return new EvaluationOutcome(summary, false);
    }

    public UUID transactionId() {
        return summary.transactionId();
    }
}
