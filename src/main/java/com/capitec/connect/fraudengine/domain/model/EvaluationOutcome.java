package com.capitec.connect.fraudengine.domain.model;

import java.util.UUID;

/**
 * The result of an evaluation request, together with what the service did about it.
 *
 * <p>An evaluation request is identified by its transaction id, so the same request can arrive
 * twice: a client that never saw the first response retries, and a caller that lost the race to a
 * concurrent submission of the same id finds the row already there. Both are answered with the
 * evaluation that is stored, and both are ordinary outcomes rather than errors.
 *
 * <p>{@link #created()} is what separates the two for the caller. It exists because the two are
 * indistinguishable in the body: a replay returns exactly the evaluation the first call returned,
 * which is the point. Only the status code tells them apart, so the service has to say which one
 * it did rather than leave the controller to guess from the data.
 *
 * @param summary the evaluation of the transaction, freshly computed or read back
 * @param created whether this call stored the transaction, as opposed to answering with one that
 *                was already stored under the same id
 */
public record EvaluationOutcome(EvaluationSummary summary, boolean created) {

    public EvaluationOutcome {
        if (summary == null) {
            throw new IllegalArgumentException("Evaluation summary must not be null");
        }
    }

    /** The outcome of a request that stored a new transaction. */
    public static EvaluationOutcome created(EvaluationSummary summary) {
        return new EvaluationOutcome(summary, true);
    }

    /** The outcome of a request answered from an already stored transaction with the same id. */
    public static EvaluationOutcome replayed(EvaluationSummary summary) {
        return new EvaluationOutcome(summary, false);
    }

    /** The id of the transaction this outcome is about. */
    public UUID transactionId() {
        return summary.transactionId();
    }
}
