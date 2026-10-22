package com.capitec.connect.fraudengine.infrastructure.lock;

import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisoryAccountLockTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SimpleMeterRegistry meterRegistry;
    private PostgresAdvisoryAccountLock accountLock;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        accountLock = new PostgresAdvisoryAccountLock(jdbcTemplate, new FraudEngineProperties(), meterRegistry);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void lockIsRequestedWithATransactionLocalTimeout() {
        withActiveTransaction();

        accountLock.lockForEvaluation("ACC-1001");

        verify(jdbcTemplate).queryForObject(anyString(), eq(String.class), eq("2000ms"));
        verify(jdbcTemplate).queryForObject(anyString(), eq(Object.class),
            eq(AccountLockKeys.forAccount("ACC-1001")));
    }

    @Test
    void lockKeysAreStableAndDistinctPerAccount() {
        assertEquals(AccountLockKeys.forAccount("ACC-1001"), AccountLockKeys.forAccount("ACC-1001"));
        assertNotEquals(AccountLockKeys.forAccount("ACC-1001"), AccountLockKeys.forAccount("ACC-1002"));
    }

    @Test
    void lockTimeoutIsTranslatedIntoAccountLockTimeoutException() {
        withActiveTransaction();
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any()))
            .thenReturn("2000ms");
        when(jdbcTemplate.queryForObject(anyString(), eq(Object.class), any()))
            .thenThrow(new DataAccessResourceFailureException("canceling statement due to lock timeout",
                new SQLException("canceling statement due to lock timeout", "55P03")));

        assertThrows(AccountLockTimeoutException.class, () -> accountLock.lockForEvaluation("ACC-1001"));
    }

    @Test
    void otherDataAccessFailuresAreNotMaskedAsLockTimeouts() {
        withActiveTransaction();
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any()))
            .thenReturn("2000ms");
        when(jdbcTemplate.queryForObject(anyString(), eq(Object.class), any()))
            .thenThrow(new DataAccessResourceFailureException("connection reset",
                new SQLException("connection reset", "08006")));

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
            () -> accountLock.lockForEvaluation("ACC-1001"));

        assertEquals("connection reset", thrown.getMessage());
    }

    @Test
    void lockIsRefusedOutsideATransaction() {
        assertThrows(IllegalStateException.class, () -> accountLock.lockForEvaluation("ACC-1001"));

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class), any());
    }

    @Test
    void lockWaitIsMeasured() {
        withActiveTransaction();

        accountLock.lockForEvaluation("ACC-1001");

        assertTrue(meterRegistry.get("fraud.account.lock.wait").tag("outcome", "acquired").timer().count() > 0);
    }

    private void withActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
}
