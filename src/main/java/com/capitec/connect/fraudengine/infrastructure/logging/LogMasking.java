package com.capitec.connect.fraudengine.infrastructure.logging;

import java.util.Objects;

/**
 * Masks account identifiers before they reach the log.
 *
 * <p>Account ids are personal data: they identify a customer, so a log file that contains them in
 * full is a customer record in everything but name. Keeping a short prefix and suffix is enough to
 * correlate two lines about the same account while the file is being read, and short enough that it
 * cannot be used to enumerate accounts afterwards.
 *
 * <p>This is not a substitute for access control on the log store. It limits what a leaked,
 * copied or over-retained log file gives away, which is a different and more common failure.
 */
public final class LogMasking {

    /** Characters kept at the start of the value. */
    private static final int VISIBLE_PREFIX = 3;

    /** Characters kept at the end of the value. */
    private static final int VISIBLE_SUFFIX = 2;

    /** Below this the value is replaced entirely rather than partially masked. */
    private static final int MINIMUM_LENGTH = 8;

    private LogMasking() {
    }

    /**
     * Returns a display form of an account id that keeps its first three and last two characters.
     *
     * @param accountId the account id to mask, may be null
     * @return the masked account id, or {@code "<none>"} when there is nothing to mask
     */
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
