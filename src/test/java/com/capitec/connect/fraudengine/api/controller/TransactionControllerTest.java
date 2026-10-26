package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import com.capitec.connect.fraudengine.api.exception.GlobalExceptionHandler;
import com.capitec.connect.fraudengine.domain.model.EvaluationOutcome;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.RuleEvaluationResult;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.HttpEvaluationService;
import com.capitec.connect.fraudengine.engine.service.TransactionQueryService;
import com.capitec.connect.fraudengine.infrastructure.security.AlertVisibility;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Spy
    private AccountAccessPolicy accountAccessPolicy = new AccountAccessPolicy();

    @Spy
    private AlertVisibility alertVisibility = new AlertVisibility();

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

        when(httpEvaluationService.evaluate(any(TransactionRequestDto.class)))
            .thenReturn(EvaluationOutcome.created(summary));

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
    void aRetryOfTheSameRequestIsAnsweredWith200AndTheIdenticalEvaluation() throws Exception {
        TransactionRequestDto requestDto = FraudTestDataFactory.transactionRequest()
            .amount(new BigDecimal("60000.00"))
            .build();

        FraudAlert alert = FraudTestDataFactory.fraudAlert()
            .transactionId(requestDto.id())
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("High amount transaction")
            .build();
        RuleEvaluationResult result =
            new RuleEvaluationResult("HighAmountRule", true, 40, Severity.HIGH, "High amount transaction");
        EvaluationSummary summary = FraudTestDataFactory.evaluationSummary(
            requestDto.id(), "ACC-1001", 40, Severity.HIGH, List.of(result), List.of(alert));
        String payload = objectMapper.writeValueAsString(requestDto);

        when(httpEvaluationService.evaluate(any(TransactionRequestDto.class)))
            .thenReturn(EvaluationOutcome.created(summary));
        String created = mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        when(httpEvaluationService.evaluate(any(TransactionRequestDto.class)))
            .thenReturn(EvaluationOutcome.replayed(summary));
        String replayed = mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // A client that never saw the 201 has to be able to recognise its own earlier success
        // from the body alone, so the two answers must be the same evaluation.
        assertEquals(created, replayed,
            "a replay must return exactly what the first evaluation returned");
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

    @Test
    void analystSeesTheRuleNamesAndScoresTheAccountHolderDoesNot() throws Exception {
        UUID txId = UUID.randomUUID();
        TransactionDetailsResponseDto details = detailsWithAlert(txId, "ACC-1001");
        when(transactionQueryService.getDetails(txId)).thenReturn(details);

        mockMvc.perform(get("/api/v1/transactions/" + txId)
                .with(authenticatedAs("SOMEONE-ELSE", "ROLE_FRAUD_ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRiskScore").value(40))
            .andExpect(jsonPath("$.alerts[0].ruleName").value("HighAmountRule"))
            .andExpect(jsonPath("$.alerts[0].riskScore").value(40))
            .andExpect(jsonPath("$.alerts[0].severity").value("HIGH"));
    }

    @Test
    void accountHolderSeesSeverityButNeitherTheRuleNorItsScore() throws Exception {
        UUID txId = UUID.randomUUID();
        TransactionDetailsResponseDto details = detailsWithAlert(txId, "ACC-1001");
        when(transactionQueryService.getDetails(txId)).thenReturn(details);

        mockMvc.perform(get("/api/v1/transactions/" + txId)
                .with(authenticatedAs("ACC-1001", "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isOk())
            // The parent total would give the redacted per-alert scores away.
            .andExpect(jsonPath("$.totalRiskScore").doesNotExist())
            .andExpect(jsonPath("$.alerts[0].ruleName").doesNotExist())
            .andExpect(jsonPath("$.alerts[0].riskScore").doesNotExist())
            // What the customer can act on stays.
            .andExpect(jsonPath("$.alerts[0].severity").value("HIGH"))
            .andExpect(jsonPath("$.alerts[0].reason").isNotEmpty())
            .andExpect(jsonPath("$.overallSeverity").value("HIGH"));
    }

    @Test
    void anotherAccountsTransactionIsReportedAsNotFoundNotForbidden() throws Exception {
        UUID txId = UUID.randomUUID();
        TransactionDetailsResponseDto details = detailsWithAlert(txId, "SOMEONE-ELSE");
        when(transactionQueryService.getDetails(txId)).thenReturn(details);

        mockMvc.perform(get("/api/v1/transactions/" + txId)
                .with(authenticatedAs("ACC-1001", "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void notYoursAndDoesNotExistAreIndistinguishable() throws Exception {
        UUID ownedBySomeoneElse = UUID.randomUUID();
        UUID doesNotExist = UUID.randomUUID();
        TransactionDetailsResponseDto someoneElses = detailsWithAlert(ownedBySomeoneElse, "SOMEONE-ELSE");
        when(transactionQueryService.getDetails(ownedBySomeoneElse)).thenReturn(someoneElses);
        when(transactionQueryService.getDetails(doesNotExist)).thenThrow(
            new TransactionNotFoundException(doesNotExist));

        // A 403 here would confirm the transaction exists and merely belongs to somebody else,
        // which is the whole difference an attacker needs to enumerate transaction ids.
        var notYours = mockMvc.perform(get("/api/v1/transactions/" + ownedBySomeoneElse)
                .with(authenticatedAs("ACC-1001", "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        var missing = mockMvc.perform(get("/api/v1/transactions/" + doesNotExist)
                .with(authenticatedAs("ACC-1001", "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        // Everything that could distinguish the two, minus the timestamp, which is the instant the
        // request was served and so legitimately differs between two calls.
        String notYoursBody = withoutTimestamp(notYours).replace(ownedBySomeoneElse.toString(), "<id>");
        String missingBody = withoutTimestamp(missing).replace(doesNotExist.toString(), "<id>");
        assertEquals(missingBody, notYoursBody,
            "a caller must not be able to tell a transaction they may not read from one that does not exist");
    }

    private String withoutTimestamp(String problemJson) {
        return problemJson.replaceAll("\"timestamp\":[0-9.]+", "\"timestamp\":\"<ignored>\"");
    }

    /**
     * Standalone MockMvc has no security filter chain, so nothing bridges the security context to
     * {@code request.getUserPrincipal()}, which is where the controller's {@code Authentication}
     * parameter is read from. Setting the principal directly is what that bridge would do.
     */
    private RequestPostProcessor authenticatedAs(String accountId, String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("account_id", accountId).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority(role)));
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private TransactionDetailsResponseDto detailsWithAlert(UUID txId, String accountId) {
        FraudAlert alert = FraudTestDataFactory.fraudAlert()
            .transactionId(txId)
            .accountId(accountId)
            .riskScore(40)
            .severity(Severity.HIGH)
            .reason("Amount 60000.00 exceeds HIGH threshold of 50000.00")
            .build();
        return new TransactionDetailsResponseDto(txId, accountId, new BigDecimal("60000.00"),
            "ZAR", "GROCERIES", "Market", "Cape Town", Instant.now(), true, 40,
            Severity.HIGH, List.of(fraudAlertMapper.toResponseDto(alert)));
    }
}
