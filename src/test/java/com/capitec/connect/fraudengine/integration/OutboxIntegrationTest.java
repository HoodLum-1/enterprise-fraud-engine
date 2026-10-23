package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.domain.model.OutboxStatus;
import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import com.capitec.connect.fraudengine.infrastructure.entity.EvaluationOutboxEntity;
import com.capitec.connect.fraudengine.infrastructure.messaging.EvaluationOutboxPublisher;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the outbox lifecycle against a real PostgreSQL and Kafka: claim, publish, acknowledge,
 * park and prune.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
// The scheduled publisher would race with the assertions below, so its poll interval is pushed out.
@TestPropertySource(properties = "fraud.engine.messaging.outbox-poll-delay-ms=3600000")
class OutboxIntegrationTest {

    private static final String BATCH_EVENT_KEY = "ACC-OUTBOX-BATCH-TEST";

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
    private EvaluationOutboxPublisher publisher;

    @Autowired
    private EvaluationOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FraudEngineProperties properties;

    @Test
    void aPendingEventIsPublishedAndMarkedDelivered() throws Exception {
        UUID id = UUID.randomUUID();
        insertPending(id, "TRANSACTION_EVALUATED", evaluationPayload(), 0);

        publisher.publishPendingEvents();

        EvaluationOutboxEntity row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED, row.getStatus());
        assertNotNull(row.getPublishedAt());
        assertNull(row.getClaimedAt());
        assertNull(row.getLeaseExpiresAt());
    }

    @Test
    void onePollDrainsMoreThanOneFullBatch() {
        String eventKey = "ACC-OUTBOX-DRAIN-TEST";
        int total = properties.getMessaging().getBatchSize() * 2 + 5;
        for (int index = 0; index < total; index++) {
            insertPending(UUID.randomUUID(), eventKey, "TRANSACTION_EVALUATED", evaluationPayload(), 0);
        }

        publisher.publishPendingEvents();

        Long published = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM event_outbox WHERE event_key = ? AND status = 'PUBLISHED'",
            Long.class, eventKey);
        assertEquals(total, published, "one poll should keep claiming while batches come back full");
    }

    @Test
    void anEventThatExhaustsItsRetriesIsParkedAsFailed() {
        UUID id = UUID.randomUUID();
        insertPending(id, "NOT_A_SUPPORTED_EVENT", "{}", properties.getMessaging().getMaxAttempts());

        publisher.publishPendingEvents();

        EvaluationOutboxEntity row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.FAILED, row.getStatus());
        assertNotNull(row.getLastError());
        assertNull(row.getNextAttemptAt());
    }

    @Test
    void aFailedEventIsRescheduledInsteadOfBeingParked() {
        UUID id = UUID.randomUUID();
        insertPending(id, "NOT_A_SUPPORTED_EVENT", "{}", 1);

        publisher.publishPendingEvents();

        EvaluationOutboxEntity row = outboxRepository.findById(id).orElseThrow();
        assertEquals(OutboxStatus.PENDING, row.getStatus());
        assertNotNull(row.getNextAttemptAt());
        assertTrue(row.getNextAttemptAt().isAfter(Instant.now()));
    }

    @Test
    void deliveredEventsArePrunedOnceTheyAreOlderThanTheRetentionPeriod() {
        UUID published = UUID.randomUUID();
        insertPending(published, "TRANSACTION_EVALUATED", evaluationPayload(), 0);
        jdbcTemplate.update(
            "UPDATE event_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), published);

        UUID recent = UUID.randomUUID();
        insertPending(recent, "TRANSACTION_EVALUATED", evaluationPayload(), 0);
        jdbcTemplate.update(
            "UPDATE event_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), recent);

        publisher.prunePublishedEvents();

        assertTrue(outboxRepository.findById(published).isEmpty(), "the old delivered row should be pruned");
        assertTrue(outboxRepository.findById(recent).isPresent(), "the recent row should be kept");
    }

    @Test
    void pruningIsBoundedToOneBatch() {
        int batchSize = properties.getMessaging().getPruneBatchSize();
        for (int index = 0; index < batchSize + 2; index++) {
            UUID id = UUID.randomUUID();
            insertPending(id, BATCH_EVENT_KEY, "TRANSACTION_EVALUATED", evaluationPayload(), 0);
            jdbcTemplate.update(
                "UPDATE event_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), id);
        }

        publisher.prunePublishedEvents();

        long remaining = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM event_outbox WHERE event_key = ? AND status = 'PUBLISHED'",
            Long.class, BATCH_EVENT_KEY);
        assertEquals(2, remaining, "only one batch should be deleted per sweep");
    }

    private void insertPending(UUID id, String eventType, String payload, int attempts) {
        insertPending(id, "ACC-OUTBOX-TEST", eventType, payload, attempts);
    }

    private void insertPending(UUID id, String eventKey, String eventType, String payload, int attempts) {
        jdbcTemplate.update(
            "INSERT INTO event_outbox (id, event_key, event_type, payload, created_at, attempts, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
            id, eventKey, eventType, payload, Timestamp.from(Instant.now()), attempts);
    }

    private String evaluationPayload() {
        return """
            {"eventId":"%s","eventType":"TRANSACTION_EVALUATED","timestamp":"2026-01-01T00:00:00Z",\
            "transactionId":"%s","accountId":"ACC-OUTBOX-TEST","amount":100.00,"currency":"ZAR",\
            "category":"GROCERIES","merchantName":"Test","location":"Cape Town","totalRiskScore":0,\
            "overallSeverity":"LOW","isFraudulent":false,"triggeredRules":[],"alertsGenerated":0}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());
    }
}
