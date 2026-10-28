package com.capitec.connect.fraudengine.domain.model;


public enum Severity {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return other != null && rank >= other.rank;
    }
}
