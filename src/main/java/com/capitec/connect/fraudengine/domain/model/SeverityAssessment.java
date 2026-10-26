package com.capitec.connect.fraudengine.domain.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * The one place where the overall severity of a transaction is derived.
 *
 * <p>Evaluation computes this while the transaction is written, and the query path recomputes it
 * from the stored alerts when the transaction is read back. Both have to use the same rule, or
 * the two views of one transaction disagree. That is exactly what happened while the thresholds
 * were duplicated: the engine short-circuited to {@code LOW} for a zero cumulative score, which a
 * caller could reach with a configured rule worth no points but a {@code CRITICAL} severity, and
 * the query path then raised it again from the stored alert severities.
 *
 * <p>The thresholds are kept here rather than in configuration on purpose. They are the definition
 * of the bands, not a tuning knob: a deployment that moved them would make every transaction
 * already on record read back at a different severity.
 */
public final class SeverityAssessment {

    private static final int MEDIUM_SCORE = 25;
    private static final int HIGH_SCORE = 50;
    private static final int CRITICAL_SCORE = 80;

    private SeverityAssessment() {
    }

    /**
     * Derives the overall severity from the cumulative risk score and the severities of the
     * individual findings.
     *
     * <p>The result is the more severe of the two views: the highest severity among the findings,
     * or whatever the cumulative score reaches on its own. A transaction with no findings at all is
     * {@code LOW}.
     *
     * @param riskScore the cumulative risk score of the findings
     * @param severities the severity of each finding, empty when there are none
     * @return the overall severity
     */
    public static Severity assess(int riskScore, Collection<Severity> severities) {
        Severity fromFindings = severities.stream()
            .filter(Objects::nonNull)
            .max(Comparator.comparingInt(Severity::getRank))
            .orElse(Severity.LOW);
        Severity fromScore = severityForScore(riskScore);
        return fromFindings.isAtLeast(fromScore) ? fromFindings : fromScore;
    }

    private static Severity severityForScore(int riskScore) {
        if (riskScore >= CRITICAL_SCORE) {
            return Severity.CRITICAL;
        }
        if (riskScore >= HIGH_SCORE) {
            return Severity.HIGH;
        }
        if (riskScore >= MEDIUM_SCORE) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }
}
