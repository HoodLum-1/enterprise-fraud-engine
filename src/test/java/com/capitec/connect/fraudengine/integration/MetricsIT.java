package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the metrics are actually exposed on the Prometheus endpoint, not just registered.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
// Spring Boot disables metrics export in tests unless this is present.
@AutoConfigureObservability
class MetricsIT {

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
    private TestRestTemplate restTemplate;

    @Test
    void thePrometheusEndpointExposesTheFraudMetrics() {
        TransactionRequestDto request = IntegrationTestDataFactory.cleanTransaction("ACC-METRICS").build();
        ResponseEntity<String> evaluation = restTemplate.postForEntity(
            "/api/v1/transactions/evaluate", request, String.class);
        assertTrue(evaluation.getStatusCode().is2xxSuccessful(), "the evaluation should succeed");

        String scrape = restTemplate.getForObject("/actuator/prometheus", String.class);

        assertTrue(scrape.contains("fraud_evaluation_duration_seconds"), "the evaluation timer is missing");
        assertTrue(scrape.contains("fraud_evaluation_duration_seconds_bucket"),
            "the evaluation histogram is missing, so a p95 cannot be computed");
        assertTrue(scrape.contains("fraud_evaluation_total"), "the evaluation counter is missing");
        assertTrue(scrape.contains("fraud_account_lock_wait_seconds"), "the lock wait timer is missing");
        assertTrue(scrape.contains("fraud_outbox_pending"), "the outbox backlog gauge is missing");
        assertTrue(scrape.contains("fraud_outbox_oldest_pending_age_seconds"), "the outbox age gauge is missing");
        assertTrue(scrape.contains("fraud_kafka_dlq_messages"), "the dead letter gauge is missing");
        assertTrue(scrape.contains("resilience4j_circuitbreaker_state"),
            "the circuit breaker state is missing, so resilience4j-micrometer is not wired up");
    }
}
