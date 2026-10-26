package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers how ingestion treats poison messages and how the HTTP quota relates to the Kafka path.
 *
 * <p>Both matter in production: a single unparseable record must not stop the partition, and a
 * backlog on the topic must never be able to answer a paying HTTP client with 429.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "security.enabled=false",
    // One HTTP evaluation per period, so the quota exhaustion below is deterministic.
    "resilience4j.ratelimiter.instances.fraudEngine.limitForPeriod=1",
    "resilience4j.ratelimiter.instances.fraudEngine.limitRefreshPeriod=1s"
})
class KafkaIngestionIntegrationTest {

    private static final String TRANSACTION_TOPIC = "transactions-topic";
    private static final String DLQ_TOPIC = TRANSACTION_TOPIC + "-dlq";
    private static final Duration ASYNC_WAIT = Duration.ofSeconds(30);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("fraud_db")
        .withUsername("postgres")
        .withPassword("postgres");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    // Explicit as well as @ServiceConnection, matching FraudEngineIntegrationTest: the raw
    // producer and the dead letter reader below are built from the container address directly.
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void aMalformedEventGoesStraightToTheDlqAndTheFollowingEventIsStillProcessed() throws Exception {
        String poisonKey = "ACC-POISON-" + UUID.randomUUID();
        sendRaw(TRANSACTION_TOPIC, poisonKey, "{ this is not json");

        String deadLettered = awaitDeadLetter(poisonKey);
        assertNotNull(deadLettered, "a malformed payload must be dead lettered instead of retried");
        // The recoverer republishes through the application template, which uses a JsonSerializer,
        // so the payload comes back as a JSON string literal rather than raw bytes.
        assertTrue(deadLettered.contains("this is not json"),
            "the dead letter must keep the original payload, was: " + deadLettered);

        TransactionRequestDto valid = IntegrationTestDataFactory
            .cleanTransaction("ACC-AFTER-POISON-" + UUID.randomUUID()).build();
        kafkaTemplate.send(TRANSACTION_TOPIC, valid.accountId(), valid);

        // The listener survived the poison record and keeps consuming the partition.
        await(() -> transactionRepository.existsById(valid.id()));
    }

    @Test
    void kafkaIngestionDoesNotSpendTheHttpQuota() throws Exception {
        TransactionRequestDto viaKafka = IntegrationTestDataFactory
            .cleanTransaction("ACC-KAFKA-QUOTA-" + UUID.randomUUID()).build();
        kafkaTemplate.send(TRANSACTION_TOPIC, viaKafka.accountId(), viaKafka);
        await(() -> transactionRepository.existsById(viaKafka.id()));

        // The quota allows one HTTP evaluation per second. Kafka has not spent any of it, so the
        // first call still succeeds and only the second one is rejected.
        TransactionRequestDto firstHttp = IntegrationTestDataFactory
            .cleanTransaction("ACC-HTTP-QUOTA-1-" + UUID.randomUUID()).build();
        TransactionRequestDto secondHttp = IntegrationTestDataFactory
            .cleanTransaction("ACC-HTTP-QUOTA-2-" + UUID.randomUUID()).build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(firstHttp)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(secondHttp)))
            .andExpect(status().isTooManyRequests());

        assertTrue(transactionRepository.existsById(viaKafka.id()),
            "the Kafka evaluation must have been persisted, not rejected by the HTTP quota");
    }

    /** Sends the payload as raw text, so a body that is not JSON stays not JSON on the wire. */
    private void sendRaw(String topic, String key, String payload) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        }
    }

    /**
     * Reads the dead letter queue directly from the container, so this assertion does not depend
     * on the application's own producer or on how the Spring context binds bootstrap servers.
     */
    private String awaitDeadLetter(String key) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-check-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        Instant deadline = Instant.now().plus(ASYNC_WAIT);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(DLQ_TOPIC));
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record.value();
                    }
                }
            }
        }
        return null;
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(ASYNC_WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous Kafka processing");
    }
}
