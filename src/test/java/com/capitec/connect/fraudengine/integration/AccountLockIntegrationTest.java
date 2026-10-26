package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.engine.service.AccountLock;
import com.capitec.connect.fraudengine.engine.service.FraudEvaluationService;
import com.capitec.connect.fraudengine.infrastructure.lock.AccountLockKeys;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-account advisory lock against a real PostgreSQL instance. The unit tests run on
 * H2, which has no advisory lock support, so the locking behaviour is only provable here.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
class AccountLockIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("fraud_db")
        .withUsername("postgres")
        .withPassword("postgres");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private FraudEvaluationService fraudEvaluationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theLockIsHeldForTheDurationOfTheTransaction() throws Exception {
        String accountId = "ACC-LOCK-" + UUID.randomUUID();

        inNewTransaction(() -> {
            accountLock.lockForEvaluation(accountId);

            // A different session must not be able to take the lock while the transaction is open.
            assertFalse(tryAcquireFromOtherSession(AccountLockKeys.forAccount(accountId)),
                "the account lock should still be held by the open transaction");
        });

        assertTrue(tryAcquireFromOtherSession(AccountLockKeys.forAccount(accountId)),
            "the account lock should be released when the transaction ends");
    }

    @Test
    void aContendedAccountTimesOutInsteadOfWaitingForever() {
        String accountId = "ACC-LOCK-" + UUID.randomUUID();
        TransactionTemplate requiresNew = requiresNewTransaction();

        requiresNew.executeWithoutResult(status -> {
            accountLock.lockForEvaluation(accountId);

            // The outer transaction holds the lock; a second transaction for the same account must
            // hit the configured lock_timeout rather than block indefinitely.
            AccountLockTimeoutException thrown = assertThrows(AccountLockTimeoutException.class,
                () -> requiresNew.executeWithoutResult(inner -> accountLock.lockForEvaluation(accountId)));

            assertEquals(accountId, thrown.getAccountId());
        });
    }

    @Test
    void differentAccountsDoNotBlockEachOther() {
        String first = "ACC-LOCK-" + UUID.randomUUID();
        String second = "ACC-LOCK-" + UUID.randomUUID();

        inNewTransaction(() -> {
            accountLock.lockForEvaluation(first);
            accountLock.lockForEvaluation(second);
        });
    }

    @Test
    void simultaneousEvaluationsOfOneAccountTriggerVelocityExactlyOnce() throws Exception {
        String accountId = "ACC-LOCK-" + UUID.randomUUID();
        Instant baseTime = Instant.now().minusSeconds(60);

        // Two prior transactions put the account one transaction below the velocity threshold.
        fraudEvaluationService.evaluate(
            IntegrationTestDataFactory.velocityTransaction(accountId, baseTime, 0).build());
        fraudEvaluationService.evaluate(
            IntegrationTestDataFactory.velocityTransaction(accountId, baseTime, 10).build());

        // The pair shares an event timestamp, so the second evaluation sees the first one regardless
        // of which thread wins the advisory lock. Without the lock both could read the same two-row
        // history and neither would trigger, which is the regression this test guards against.
        TransactionRequestDto first = IntegrationTestDataFactory
            .velocityTransaction(accountId, baseTime, 20).build();
        TransactionRequestDto second = IntegrationTestDataFactory
            .velocityTransaction(accountId, baseTime, 20).build();

        CyclicBarrier startTogether = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<EvaluationOutcome> evaluateFirst = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return fraudEvaluationService.evaluate(first);
            };
            Callable<EvaluationOutcome> evaluateSecond = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return fraudEvaluationService.evaluate(second);
            };
            Future<EvaluationOutcome> firstResult = executor.submit(evaluateFirst);
            Future<EvaluationOutcome> secondResult = executor.submit(evaluateSecond);

            startTogether.await(5, TimeUnit.SECONDS);
            EvaluationSummary firstSummary = firstResult.get(30, TimeUnit.SECONDS).summary();
            EvaluationSummary secondSummary = secondResult.get(30, TimeUnit.SECONDS).summary();

            long velocityAlerts = Stream.of(firstSummary, secondSummary)
                .flatMap(summary -> summary.alerts().stream())
                .filter(alert -> "TransactionVelocityRule".equals(alert.ruleName()))
                .count();

            assertEquals(1, velocityAlerts,
                "the account lock should make exactly one of the concurrent evaluations see the "
                    + "other transaction and cross the velocity threshold");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theDatabaseRunsAtReadCommittedIsolation() {
        String isolation = inNewTransaction(() -> jdbcTemplate.queryForObject(
            "SHOW transaction_isolation", String.class));

        assertEquals("read committed", isolation);
    }

    private void inNewTransaction(Runnable work) {
        requiresNewTransaction().executeWithoutResult(status -> work.run());
    }

    private <T> T inNewTransaction(java.util.function.Supplier<T> work) {
        return requiresNewTransaction().execute(status -> work.get());
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private boolean tryAcquireFromOtherSession(long key) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                 connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
