package com.capitec.connect.fraudengine.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.capitec.connect.fraudengine.api.controller.FraudAlertController;
import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.exception.GlobalExceptionHandler;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.engine.service.AccountHistoryService;
import com.capitec.connect.fraudengine.engine.service.EvaluationOutboxService;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import com.capitec.connect.fraudengine.engine.service.FraudEvaluationService;
import com.capitec.connect.fraudengine.api.exception.AccountLockTimeoutException;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;
import com.capitec.connect.fraudengine.infrastructure.security.AlertVisibility;
import com.capitec.connect.fraudengine.infrastructure.lock.AccountLockKeys;
import com.capitec.connect.fraudengine.infrastructure.lock.PostgresAdvisoryAccountLock;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.infrastructure.mapper.TransactionMapper;
import com.capitec.connect.fraudengine.infrastructure.metrics.FraudMetrics;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The account id must not reach the log in full from any of the paths that name one.
 *
 * <p>Masking is easy to apply at one call site and to forget at the next, so this drives the real
 * code behind each of the four that log an account id and captures what the log actually receives.
 * Asserting on {@link LogMasking} alone would not catch a site that never called it.
 */
class AccountIdLogMaskingTest {

    /** The account id every case uses, chosen so a prefix-and-suffix mask cannot contain it. */
    private static final String ACCOUNT_ID = "ACC-999911112222";

    private static final String MASKED = LogMasking.mask(ACCOUNT_ID);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        appender.stop();
    }

    @Test
    void theEvaluationServiceMasksTheAccountIdItLogs() {
        attach(FraudEvaluationService.class);

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        FraudAlertRepository fraudAlertRepository = mock(FraudAlertRepository.class);
        FraudEngine engine = mock(FraudEngine.class);
        when(transactionRepository.findById(any())).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fraudAlertRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // A rule-free evaluation is enough: the line under test is the one naming the account.
        when(engine.evaluate(any(), any())).thenReturn(new EvaluationSummary(
            UUID.randomUUID(), ACCOUNT_ID, 0, Severity.LOW, List.of(), List.of()));

        FraudEvaluationService service = new FraudEvaluationService(
            transactionRepository, fraudAlertRepository, engine, new TransactionMapper(),
            new FraudAlertMapper(), new FraudEngineProperties(), mock(EvaluationOutboxService.class),
            mock(AccountHistoryService.class), accountId -> { },
            new FraudMetrics(new SimpleMeterRegistry()));

        service.evaluate(FraudTestDataFactory.transactionRequest().accountId(ACCOUNT_ID).build());

        assertAccountIdIsNotLogged(FraudEvaluationService.class);
    }

    @Test
    void theAlertControllerMasksTheAccountIdItLogs() {
        attach(FraudAlertController.class);

        FraudAlertQueryService queryService = mock(FraudAlertQueryService.class);
        Page<FraudAlertResponseDto> page = new PageImpl<>(List.of());
        when(queryService.getAlertsByAccountId(any(), any(), any(), any(), any(), any()))
            .thenReturn(page);

        FraudAlertController controller = new FraudAlertController(
            queryService, new AccountAccessPolicy(),
            new AlertVisibility());

        controller.getAlertsByAccountId(ACCOUNT_ID, null, null, null, null, PageRequest.of(0, 20), analyst());

        assertAccountIdIsNotLogged(FraudAlertController.class);
    }

    @Test
    void theAdvisoryLockMasksTheAccountIdItLogsOnTimeout() {
        attach(PostgresAdvisoryAccountLock.class);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any())).thenReturn("2000ms");
        when(jdbcTemplate.queryForObject(anyString(), eq(Object.class), any()))
            .thenThrow(new DataAccessResourceFailureException("canceling statement due to lock timeout",
                new SQLException("canceling statement due to lock timeout", "55P03")));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            PostgresAdvisoryAccountLock lock = new PostgresAdvisoryAccountLock(
                jdbcTemplate, new FraudEngineProperties(), new SimpleMeterRegistry());

            assertThrows(AccountLockTimeoutException.class,
                () -> lock.lockForEvaluation(ACCOUNT_ID));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertAccountIdIsNotLogged(PostgresAdvisoryAccountLock.class);
    }

    @Test
    void theAdvisoryLockKeepsEnoughOfTheIdToFindTheRightAccount() {
        // The operator still has to be able to tell which account was contended.
        assertTrue(ACCOUNT_ID.startsWith(MASKED.substring(0, 3)));
        assertTrue(ACCOUNT_ID.endsWith(MASKED.substring(MASKED.length() - 2)));
        assertFalse(ACCOUNT_ID.contains(MASKED.substring(3, MASKED.length() - 2)));
    }

    @Test
    void theMaskedFormIsStableSoTwoLinesAboutOneAccountStillMatch() {
        assertEquals(LogMasking.mask(ACCOUNT_ID), MASKED);
    }

    /**
     * Attaches a capture appender to the given class's logger, so the assertions read against what
     * the code actually emitted rather than against a hand-checked string.
     */
    private void attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    private void assertAccountIdIsNotLogged(Class<?> type) {
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(lines.isEmpty(), type.getSimpleName() + " was expected to log something");
        for (String line : lines) {
            assertFalse(line.contains(ACCOUNT_ID),
                type.getSimpleName() + " logged the account id in full: " + line);
        }
    }

    private JwtAuthenticationToken analyst() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("sub", "analyst").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_FRAUD_ANALYST")));
    }
}
