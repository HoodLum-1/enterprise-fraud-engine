package com.capitec.connect.fraudengine.infrastructure.logging;

import java.util.Objects;

/**
 * Masks account identifiers before they reach the log.
 */
public final class LogMasking {

    private static final int VISIBLE_PREFIX = 3;

    private static final int VISIBLE_SUFFIX = 2;

    private static final int MINIMUM_LENGTH = 8;

    private LogMasking() {
    }

    public static String mask(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank()) {
            return "<none>";
        }
        if (accountId.length() < MINIMUM_LENGTH) {
            // Too short to mask usefully; anything kept would be most of the value.
            return "*".repeat(accountId.length());
        }
        int masked = accountId.length() - VISIBLE_PREFIX - VISIBLE_SUFFIX;
        return accountId.substring(0, VISIBLE_PREFIX)
            + "*".repeat(masked)
            + accountId.substring(accountId.length() - VISIBLE_SUFFIX);
    }
}
