package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.EvaluationSummaryResponseDto;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.infrastructure.entity.FraudAlertEntity;
import com.capitec.connect.fraudengine.infrastructure.entity.TransactionEntity;
import com.capitec.connect.fraudengine.infrastructure.repository.FraudAlertRepository;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
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
@ActiveProfiles("test")
class FraudEngineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("fraud_db")
        .withUsername("postgres")
        .withPassword("postgres");

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FraudAlertRepository fraudAlertRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
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

        List<FraudAlertEntity> alerts = fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(request.accountId());
        assertTrue(alerts.isEmpty());
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

        List<FraudAlertEntity> alerts = fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(request.accountId());
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
        Instant baseTime = Instant.now().minusSeconds(120); // 2 minutes ago

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
}