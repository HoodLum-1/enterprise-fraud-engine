package com.capitec.connect.fraudengine.domain.model;

import java.util.Set;

public enum CaseStatus {
    PENDING_REVIEW,
    CONFIRMED_FRAUD,
    FALSE_POSITIVE,
    RESOLVED;

    public boolean canTransitionTo(CaseStatus next) {
        return switch (this) {
            case PENDING_REVIEW -> Set.of(CONFIRMED_FRAUD, FALSE_POSITIVE, RESOLVED).contains(next);
            case CONFIRMED_FRAUD, FALSE_POSITIVE -> next == RESOLVED;
            case RESOLVED -> false;
        };
    }
}
