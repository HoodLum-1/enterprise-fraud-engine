package com.capitec.connect.fraudengine.infrastructure.lock;

import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.engine.service.AccountLock;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.logging.LogMasking;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Per-account evaluation lock backed by a PostgreSQL transaction-scoped advisory lock.
 *
 * <p>{@code pg_advisory_xact_lock} is released automatically when the surrounding transaction
 * commits or rolls back, so a crashed or timed-out evaluation cannot strand the lock.
 *
 * <p>A {@code lock_timeout} is set for the transaction before the lock is requested so that a
 * contended account fails fast instead of blocking. Without it, a hot account would park every
 * waiting request on a Hikari connection, and the pool only has 20 connections.
 */
@Component
@ConditionalOnProperty(prefix = "fraud.engine.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PostgresAdvisoryAccountLock implements AccountLock {

    /** PostgreSQL {@code lock_not_available}, raised when {@code lock_timeout} fires. */
    private static final String LOCK_TIMEOUT_SQL_STATE = "55P03";

    private static final String SET_LOCK_TIMEOUT_SQL = "SELECT set_config('lock_timeout', ?, true)";
    private static final String ACQUIRE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";

    private final JdbcTemplate jdbcTemplate;
    private final FraudEngineProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public void lockForEvaluation(String accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // The transaction-local lock_timeout below is only honoured inside a transaction, and an
            // xact-scoped advisory lock is released at the end of the statement when autocommitting.
            throw new IllegalStateException(
                "Account lock must be acquired inside an active transaction for account " + accountId);
        }

        Duration timeout = properties.getLock().getTimeout();
        long key = AccountLockKeys.forAccount(accountId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            jdbcTemplate.queryForObject(SET_LOCK_TIMEOUT_SQL, String.class, timeout.toMillis() + "ms");
            jdbcTemplate.queryForObject(ACQUIRE_LOCK_SQL, Object.class, key);
            sample.stop(lockWaitTimer("acquired"));
        } catch (DataAccessException ex) {
            if (isLockTimeout(ex)) {
                sample.stop(lockWaitTimer("timeout"));
                log.warn("Timed out after {} waiting for the evaluation lock on account {}",
                        timeout, LogMasking.mask(accountId));
                throw new AccountLockTimeoutException(accountId, ex);
            }
            sample.stop(lockWaitTimer("error"));
            throw ex;
        }
    }

    private Timer lockWaitTimer(String outcome) {
        return meterRegistry.timer("fraud.account.lock.wait", "outcome", outcome);
    }

    /**
     * The lock timeout is raised by PostgreSQL as a data-access failure, so it is detected by
     * SQL state rather than by the translated exception type. The original SQLException is kept as
     * the cause of the translated exception, so the state is still reachable.
     */
    private boolean isLockTimeout(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && LOCK_TIMEOUT_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
