package com.capitec.connect.fraudengine.integration;

import com.capitec.connect.fraudengine.domain.model.Transaction;
import com.capitec.connect.fraudengine.engine.FraudEngine;
import com.capitec.connect.fraudengine.infrastructure.repository.TransactionRepository;
import com.capitec.connect.fraudengine.integration.util.IntegrationTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=false")
class EvaluationUnavailableIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockBean
    private FraudEngine fraudEngine;

    @Test
    void engineFailureReturns503AndDoesNotPersistAsClean() throws Exception {
        var request = IntegrationTestDataFactory.highAmountTransaction("ACC-ENGINE-DOWN").build();
        when(fraudEngine.evaluate(any(Transaction.class), anyList()))
            .thenThrow(new IllegalStateException("engine unavailable"));

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "5"))
            .andExpect(jsonPath("$.status").value(503));

        assertFalse(transactionRepository.existsById(request.id()));
    }
}
