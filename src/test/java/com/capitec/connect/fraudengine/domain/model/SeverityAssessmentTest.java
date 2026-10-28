package com.capitec.connect.fraudengine.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scale and the one assessment both paths use.
 *
 * <p>These are the assertions that keep an evaluation and its read-back from disagreeing. The
 * freeze policy compares severities too, so the ordering here is the ordering that decides whether
 * an account is frozen.
 */
class SeverityAssessmentTest {

    @Test
    void theScaleIsOrderedLowToCriticalRegardlessOfDeclarationOrder() {
        assertTrue(Severity.LOW.getRank() < Severity.MEDIUM.getRank());
        assertTrue(Severity.MEDIUM.getRank() < Severity.HIGH.getRank());
        assertTrue(Severity.HIGH.getRank() < Severity.CRITICAL.getRank());
    }

    @Test
    void isAtLeastComparesTheScaleAndNotTheDeclarationOrder() {
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.LOW));
        assertTrue(Severity.HIGH.isAtLeast(Severity.HIGH));
        assertFalse(Severity.LOW.isAtLeast(Severity.MEDIUM));
        assertFalse(Severity.LOW.isAtLeast(null));
        assertFalse(Severity.CRITICAL.isAtLeast(null));
    }

    @Test
    void noFindingsIsLowWhateverTheScoreSays() {
        assertEquals(Severity.LOW, SeverityAssessment.assess(0, List.of()));
    }

    @Test
    void theScoreRaisesTheSeverityOnItsOwn() {
        assertEquals(Severity.LOW, SeverityAssessment.assess(24, List.of()));
        assertEquals(Severity.MEDIUM, SeverityAssessment.assess(25, List.of()));
        assertEquals(Severity.HIGH, SeverityAssessment.assess(50, List.of()));
        assertEquals(Severity.CRITICAL, SeverityAssessment.assess(80, List.of()));
        assertEquals(Severity.CRITICAL, SeverityAssessment.assess(500, List.of()));
    }

    @Test
    void theHighestFindingWinsEvenWhenTheScoreIsStillLow() {
        assertEquals(Severity.CRITICAL,
            SeverityAssessment.assess(0, List.of(Severity.CRITICAL)));
        assertEquals(Severity.HIGH,
            SeverityAssessment.assess(10, List.of(Severity.LOW, Severity.HIGH)));
    }

    @Test
    void findingsAndScoreAgreeOnTheMoreSevereOfTheTwo() {
        assertEquals(Severity.CRITICAL,
            SeverityAssessment.assess(80, List.of(Severity.MEDIUM)));
        assertEquals(Severity.CRITICAL,
            SeverityAssessment.assess(10, List.of(Severity.CRITICAL)));
        assertEquals(Severity.LOW,
            SeverityAssessment.assess(0, List.of(Severity.LOW)));
    }

    @Test
    void aMissingFindingSeverityIsIgnoredRatherThanBecomingTheAnswer() {
        assertEquals(Severity.MEDIUM,
            SeverityAssessment.assess(0, java.util.Arrays.asList(null, Severity.MEDIUM)));
    }

    @Test
    void theScaleIsUsedByBothReadPathsTheSameWay() {
        assertEquals(Severity.LOW, SeverityAssessment.assess(0, List.of(Severity.LOW)));
        assertEquals(Severity.MEDIUM, SeverityAssessment.assess(0, List.of(Severity.MEDIUM)));
        assertEquals(Severity.HIGH, SeverityAssessment.assess(0, List.of(Severity.HIGH)));
        assertEquals(Severity.CRITICAL, SeverityAssessment.assess(0, List.of(Severity.CRITICAL)));
    }
}
