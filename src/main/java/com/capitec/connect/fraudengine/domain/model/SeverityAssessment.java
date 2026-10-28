package com.capitec.connect.fraudengine.domain.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

public final class SeverityAssessment {

    private static final int MEDIUM_SCORE = 25;
    private static final int HIGH_SCORE = 50;
    private static final int CRITICAL_SCORE = 80;

    private SeverityAssessment() {
    }

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
