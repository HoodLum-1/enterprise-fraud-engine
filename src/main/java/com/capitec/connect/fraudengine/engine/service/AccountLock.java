package com.capitec.connect.fraudengine.engine.service;

/**
 * Serialises the evaluation of transactions that belong to the same account.
 *
 * <p>Evaluation reads the account's transaction history and then writes a new transaction. Two
 * concurrent evaluations for one account would each read a history that excludes the other's
 * uncommitted write, so the velocity and location-mismatch rules could both be missed. Taking a
 * per-account lock before the read serialises those evaluations.
 *
 * <p>Implementations must be called inside an active transaction: the lock is expected to be held
 * for the duration of the surrounding transaction, not for the duration of the call.
 */
public interface AccountLock {

    /**
     * Acquires the evaluation lock for the given account, blocking until it is granted.
     *
     * @param accountId the account whose evaluations must be serialised
     * @throws com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException
     *         if the lock could not be acquired within the configured timeout
     */
    void lockForEvaluation(String accountId);
}
