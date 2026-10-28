package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class TransactionIdempotencyIT {

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
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void aRetryOfTheSameRequestIsAnsweredWithTheEvaluationItAlreadyProduced() throws Exception {
        TransactionRequestDto request = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-001").build();

        String created = evaluate(request).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        int alertsAfterFirst = alertsOn(request);

        String replayed = evaluate(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertEquals(created, replayed, "a retry must return exactly what the first call returned");
        assertEquals(alertsAfterFirst, alertsOn(request),
            "a retry must not evaluate the transaction a second time");
        assertEquals(1, countRows("select count(*) from transactions where id = ?", request),
            "the transaction must be stored once");
    }

    @Test
    void aRetryThatSpellsTheSameTransactionDifferentlyIsStillTheSameTransaction() throws Exception {
        TransactionRequestDto original = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-002").build();

        String created = evaluate(original).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        TransactionRequestDto respelt = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-002")
            .id(original.id())
            .amount(new BigDecimal("75000.0"))
            .category("electronics")
            .timestamp(original.timestamp())
            .build();

        String replayed = evaluate(respelt).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertEquals(created, replayed);
        assertEquals(1, countRows("select count(*) from transactions where id = ?", original));
    }

    @Test
    void theSameIdDescribingADifferentTransactionIsRefusedAndLeavesTheStoredOneAlone() throws Exception {
        TransactionRequestDto original = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-003").build();
        evaluate(original).andExpect(status().isCreated());

        TransactionRequestDto conflicting = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-003")
            .id(original.id())
            .amount(new BigDecimal("1.00"))
            .timestamp(original.timestamp())
            .build();

        evaluate(conflicting).andExpect(status().isConflict());

        TransactionEntity stored = transactionRepository.findById(original.id()).orElseThrow();
        assertEquals(original.amount(), stored.getAmount());
        assertEquals(original.category(), stored.getCategory());
        assertEquals(original.accountId(), stored.getAccountId());
        assertEquals(1, countRows("select count(*) from transactions where id = ?", original));
    }

    @Test
    void concurrentSubmissionsOfOneIdResolveToAReplayOrAConflictNeverAnError() throws Exception {
        TransactionRequestDto request = IntegrationTestDataFactory
            .highAmountTransaction("ACC-IDEM-004").build();
        String payload = objectMapper.writeValueAsString(request);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> submit = () -> {
                startTogether.await(20, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/v1/transactions/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andReturn();
                return result.getResponse().getStatus();
            };

            Future<Integer> first = executor.submit(submit);
            Future<Integer> second = executor.submit(submit);
            int firstStatus = first.get(60, TimeUnit.SECONDS);
            int secondStatus = second.get(60, TimeUnit.SECONDS);

            assertOrdinaryOutcome(firstStatus);
            assertOrdinaryOutcome(secondStatus);
            assertEquals(1, Stream.of(firstStatus, secondStatus).filter(status -> status == 201).count(),
                "exactly one submission may store the transaction");
            assertEquals(1, countRows("select count(*) from transactions where id = ?", request),
                "the transaction must be stored once however many callers raced for it");
            assertEquals(1, alertsOn(request), "alerts must not be duplicated by a retry");
        } finally {
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions evaluate(TransactionRequestDto request)
        throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/evaluate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
    }

    private void assertOrdinaryOutcome(int status) {
        assertTrue(status < 500,
            "a duplicate submission must never be reported as a server error, got " + status);
        assertTrue(Set.of(200, 201, 409).contains(status),
            "a duplicate submission must be answered with a replay or a conflict, not " + status);
    }

    private int alertsOn(TransactionRequestDto request) {
        return fraudAlertRepository.findByTransactionIdOrderByFlaggedAtDesc(request.id()).size();
    }

    private int countRows(String sql, TransactionRequestDto request) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, request.id());
        return count == null ? 0 : count;
    }
}
