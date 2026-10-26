package com.capitec.connect.fraudengine.domain.model;

/**
 * The severity of a single alert, or of an evaluation as a whole.
 *
 * <p>The scale is carried by an explicit rank rather than by {@link Enum#ordinal()}. Freeze policy
 * comparisons used to compare ordinals, which silently depends on the declaration order: moving
 * {@code MEDIUM} would have changed which accounts get frozen, and nothing in the code says so.
 */
public enum Severity {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /** The position of this severity on the low-to-critical scale. */
    public int getRank() {
        return rank;
    }

    /** Whether this severity is at least as severe as {@code other}. */
    public boolean isAtLeast(Severity other) {
        // A null minimum means no minimum is configured, so no severity qualifies on its own and
        // only the risk-score threshold decides. Treating null as "everything passes" would freeze
        // accounts because a property was left out.
        return other != null && rank >= other.rank;
    }
}
