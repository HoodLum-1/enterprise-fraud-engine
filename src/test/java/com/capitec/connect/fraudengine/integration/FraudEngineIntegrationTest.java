package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.EvaluationSummaryResponseDto;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudCaseRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.FreezeAuditRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.EvaluationOutboxRepository;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class FraudEngineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("fraud_db")
        .withUsername("postgres")
        .withPassword("postgres");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FraudAlertRepository fraudAlertRepository;

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private FreezeAuditRepository freezeAuditRepository;

    @Autowired
    private EvaluationOutboxRepository outboxRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void productionMigrationsDoNotLeaveDemoDataBehind() {
        assertEquals(0, countDemoRows("transactions"),
            "the production migration path must not leave ACC-DEMO transactions behind");
        assertEquals(0, countDemoRows("fraud_alerts"),
            "the production migration path must not leave ACC-DEMO alerts behind");
    }

    @Test
    void evaluateTransaction_shouldPersistTransactionAndReturnEvaluationSummary() throws Exception {
        TransactionRequestDto request = IntegrationTestDataFactory.cleanTransaction("ACC-INT-001").build();

        String responseJson = mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transactionId").value(request.id().toString()))
            .andExpect(jsonPath("$.accountId").value(request.accountId()))
            .andExpect(jsonPath("$.totalRiskScore").value(0))
            .andExpect(jsonPath("$.overallSeverity").value("LOW"))
            .andExpect(jsonPath("$.isFraudulent").value(false))
            .andExpect(jsonPath("$.alerts").isEmpty())
            .andReturn().getResponse().getContentAsString();

        EvaluationSummaryResponseDto body = objectMapper.readValue(responseJson, EvaluationSummaryResponseDto.class);

        TransactionEntity savedTx = transactionRepository.findById(request.id()).orElseThrow();
        assertEquals(request.accountId(), savedTx.getAccountId());
        assertEquals(request.amount(), savedTx.getAmount());
        assertEquals(request.currency(), savedTx.getCurrency());
        assertEquals(request.category(), savedTx.getCategory());
        assertEquals(request.merchantName(), savedTx.getMerchantName());
        assertEquals(request.location(), savedTx.getLocation());
        assertEquals(request.timestamp(), savedTx.getTimestamp());

        assertTrue(fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(
            request.accountId(), org.springframework.data.domain.PageRequest.of(0, 20))
            .isEmpty());
    }

    @Test
    void evaluateTransaction_highAmount_shouldTriggerFraudAlertAndPersist() throws Exception {
        TransactionRequestDto request = IntegrationTestDataFactory.highAmountTransaction("ACC-INT-002").build();

        String responseJson = mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transactionId").value(request.id().toString()))
            .andExpect(jsonPath("$.totalRiskScore").value(40))
            .andExpect(jsonPath("$.overallSeverity").value("HIGH"))
            .andExpect(jsonPath("$.isFraudulent").value(true))
            .andExpect(jsonPath("$.alerts").isArray())
            .andExpect(jsonPath("$.alerts.length()").value(1))
            .andExpect(jsonPath("$.alerts[0].ruleName").value("HighAmountRule"))
            .andExpect(jsonPath("$.alerts[0].riskScore").value(40))
            .andExpect(jsonPath("$.alerts[0].severity").value("HIGH"))
            .andReturn().getResponse().getContentAsString();

        EvaluationSummaryResponseDto body = objectMapper.readValue(responseJson, EvaluationSummaryResponseDto.class);

        TransactionEntity savedTx = transactionRepository.findById(request.id()).orElseThrow();
        assertEquals(request.amount(), savedTx.getAmount());

        List<FraudAlertEntity> alerts = fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(
            request.accountId(), org.springframework.data.domain.PageRequest.of(0, 20))
            .getContent();
        assertEquals(1, alerts.size());
        FraudAlertEntity savedAlert = alerts.getFirst();
        assertEquals(request.id(), savedAlert.getTransactionId());
        assertEquals(request.accountId(), savedAlert.getAccountId());
        assertEquals("HighAmountRule", savedAlert.getRuleName());
        assertEquals(40, savedAlert.getRiskScore());
        assertEquals(Severity.HIGH, savedAlert.getSeverity());
        assertTrue(savedAlert.getReason().contains("exceeds threshold"));
    }

    @Test
    void evaluateTransaction_duplicateId_shouldReturn409Conflict() throws Exception {
        UUID fixedId = UUID.randomUUID();
        TransactionRequestDto request = IntegrationTestDataFactory.duplicateTransaction(fixedId)
            .accountId("ACC-INT-003")
            .build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void evaluateTransaction_invalidPayload_shouldReturn400WithInvalidParams() throws Exception {
        TransactionRequestDto request = IntegrationTestDataFactory.invalidPayloadTransaction().build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.invalidParams").exists())
            .andExpect(jsonPath("$.invalidParams.amount").exists())
            .andExpect(jsonPath("$.invalidParams.currency").exists())
            .andExpect(jsonPath("$.invalidParams.accountId").exists())
            .andExpect(jsonPath("$.invalidParams.category").exists())
            .andExpect(jsonPath("$.invalidParams.timestamp").exists());
    }

    @Test
    void evaluateTransaction_velocityRule_shouldTriggerWhenThresholdExceeded() throws Exception {
        String accountId = "ACC-INT-VELOCITY";
        Instant baseTime = Instant.now().minusSeconds(120);

        TransactionRequestDto[] velocitySeries = IntegrationTestDataFactory.velocitySeries(accountId, baseTime, 4, 20);

        for (TransactionRequestDto request : velocitySeries) {
            mockMvc.perform(post("/api/v1/transactions/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        }

        Instant finalTimestamp = baseTime.plusSeconds(85);
        TransactionRequestDto finalRequest = IntegrationTestDataFactory.velocityTransaction(accountId, baseTime, 85)
            .build();

        String responseJson = mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(finalRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalRiskScore").value(org.hamcrest.Matchers.greaterThanOrEqualTo(50)))
            .andExpect(jsonPath("$.isFraudulent").value(true))
            .andReturn().getResponse().getContentAsString();

        EvaluationSummaryResponseDto body = objectMapper.readValue(responseJson, EvaluationSummaryResponseDto.class);

        boolean hasVelocityAlert = body.alerts().stream()
            .anyMatch(a -> a.ruleName().equals("TransactionVelocityRule"));
        assertTrue(hasVelocityAlert, "Should have TransactionVelocityRule alert");
    }

    @Test
    void evaluateTransaction_locationMismatchUsesOnlyPriorPersistedTransactions() throws Exception {
        String accountId = "ACC-INT-LOCATION";
        Instant firstTimestamp = Instant.now().minusSeconds(600);
        TransactionRequestDto first = IntegrationTestDataFactory.cleanTransaction(accountId)
            .location("Cape Town")
            .timestamp(firstTimestamp)
            .build();
        TransactionRequestDto second = IntegrationTestDataFactory.cleanTransaction(accountId)
            .location("Johannesburg")
            .timestamp(firstTimestamp.plusSeconds(300))
            .build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.isFraudulent").value(false));

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.isFraudulent").value(true))
            .andExpect(jsonPath("$.alerts[?(@.ruleName == 'LocationMismatchRule')]").isNotEmpty());
    }

    @Test
    void transactionKafkaInputAndOutboxCreateAuditedFraudCase() throws Exception {
        String accountId = "ACC-INT-KAFKA-" + UUID.randomUUID();
        Instant baseTime = Instant.now().minusSeconds(90);
        TransactionRequestDto fourth = null;

        for (int index = 0; index < 4; index++) {
            TransactionRequestDto request = IntegrationTestDataFactory.cleanTransaction(accountId)
                .timestamp(baseTime.plusSeconds(index * 15L))
                .build();
            if (index == 3) {
                fourth = request;
            }
            kafkaTemplate.send("transactions-topic", accountId, request).get();
        }

        assertNotNull(fourth);
        UUID transactionId = fourth.id();
        await(() -> fraudCaseRepository.existsByTransactionId(transactionId));
        await(() -> freezeAuditRepository.existsByTransactionId(transactionId));
        await(() -> outboxRepository.countByEventType("ACCOUNT_FREEZE") > 0);
        assertTrue(transactionRepository.existsById(transactionId));
    }

    private long countDemoRows(String table) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE account_id LIKE 'ACC-DEMO-%'", Long.class);
        return count == null ? 0 : count;
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous Kafka processing");
    }
}