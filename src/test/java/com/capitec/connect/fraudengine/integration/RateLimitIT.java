package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "security.enabled=false",
    "resilience4j.ratelimiter.instances.fraudEngine.limitForPeriod=1",
    "resilience4j.ratelimiter.instances.fraudEngine.limitRefreshPeriod=1h",
    "resilience4j.ratelimiter.instances.fraudEngine.timeoutDuration=0"
})
class RateLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void rejectedEvaluationReturns429WithoutSavingOrPublishing() throws Exception {
        TransactionRequestDto first = IntegrationTestDataFactory.cleanTransaction("ACC-RATE-LIMIT").build();
        TransactionRequestDto rejected = IntegrationTestDataFactory.highAmountTransaction("ACC-RATE-LIMIT").build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejected)))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.title").value("Too Many Requests"));

        assertFalse(transactionRepository.existsById(rejected.id()));
    }
}
