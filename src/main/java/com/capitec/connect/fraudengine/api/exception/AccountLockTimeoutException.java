package com.capitec.connect.fraudengine.api.exception;

/**
 * Raised when the per-account evaluation lock could not be acquired within the configured timeout.
 *
 * <p>This is deliberately <em>not</em> a {@code DataAccessException}. PostgreSQL reports a lock
 * timeout as a data-access failure, and the circuit breaker is configured to record
 * {@code DataAccessException}s. If contention surfaced as one, a single hot account could open the
 * circuit for every other caller. Contention is a load signal, not a dependency failure, so it
 * gets its own type and is listed in the circuit breaker's {@code ignoreExceptions}.
 */
public class AccountLockTimeoutException extends RuntimeException {

    private final String accountId;

    public AccountLockTimeoutException(String accountId, Throwable cause) {
        super("Timed out acquiring the evaluation lock for account " + accountId, cause);
        this.accountId = accountId;
    }

    public String getAccountId() {
        return accountId;
    }
}
