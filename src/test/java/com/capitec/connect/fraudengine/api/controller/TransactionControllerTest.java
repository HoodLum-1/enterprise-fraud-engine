package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.GlobalExceptionHandler;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.HttpEvaluationService;
import com.capitec.connect.fraudengine.engine.service.TransactionQueryService;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private HttpEvaluationService httpEvaluationService;

    @Spy
    private FraudAlertMapper fraudAlertMapper = new FraudAlertMapper();

    @Mock
    private TransactionQueryService transactionQueryService;

    @Mock
    private AccountAccessPolicy accountAccessPolicy;

    @InjectMocks
    private TransactionController transactionController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(transactionController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void testEvaluateTransaction_Success_Returns201Created() throws Exception {
        TransactionRequestDto requestDto = FraudTestDataFactory.transactionRequest()
            .amount(new BigDecimal("60000.00"))
            .build();
        UUID txId = requestDto.id();

        FraudAlert alert = FraudTestDataFactory.fraudAlert()
            .transactionId(txId)
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("High amount transaction")
            .build();

        RuleEvaluationResult result = new RuleEvaluationResult("HighAmountRule", true, 40, Severity.HIGH, "High amount transaction");

        EvaluationSummary summary = FraudTestDataFactory.evaluationSummary(
            txId,
            "ACC-1001",
            40,
            Severity.HIGH,
            List.of(result),
            List.of(alert)
        );

        when(httpEvaluationService.evaluate(any(TransactionRequestDto.class))).thenReturn(summary);

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDto)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transactionId").value(txId.toString()))
            .andExpect(jsonPath("$.accountId").value("ACC-1001"))
            .andExpect(jsonPath("$.totalRiskScore").value(40))
            .andExpect(jsonPath("$.overallSeverity").value("HIGH"))
            .andExpect(jsonPath("$.isFraudulent").value(true))
            .andExpect(jsonPath("$.alerts[0].ruleName").value("HighAmountRule"));
    }

    @Test
    void testEvaluateTransaction_Duplicate_Returns409Conflict() throws Exception {
        TransactionRequestDto requestDto = FraudTestDataFactory.transactionRequest()
            .amount(new BigDecimal("100.00"))
            .category("GROCERY")
            .merchantName("Store")
            .location("Cape Town")
            .build();
        UUID txId = requestDto.id();

        when(httpEvaluationService.evaluate(any(TransactionRequestDto.class)))
            .thenThrow(new DuplicateTransactionException(txId));

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDto)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").value("Transaction already exists with id: " + txId));
    }

    @Test
    void testEvaluateTransaction_ValidationError_Returns400BadRequest() throws Exception {
        // Invalid request with null id and empty accountId
        TransactionRequestDto invalidDto = FraudTestDataFactory.transactionRequest()
            .id(null)
            .accountId("")
            .amount(new BigDecimal("-50.00"))
            .currency("INVALID")
            .category("")
            .merchantName(null)
            .location(null)
            .timestamp(null)
            .build();

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidDto)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.invalidParams").exists());
    }
}
