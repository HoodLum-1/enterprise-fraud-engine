package com.capitec.connect.fraudengine.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "security.enabled=true")
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fraudCases_withoutAuthentication_returnsProblem401() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void fraudCases_withAuthenticatedNonAnalyst_returnsProblem403() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .authorities(() -> "ROLE_FRAUD_VIEWER")))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Forbidden"))
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void fraudCases_withAnalystRole_isAccessible() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .authorities(() -> "ROLE_FRAUD_ANALYST")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page.totalElements").value(0))
            .andExpect(jsonPath("$.size").doesNotExist());
    }

    @Test
    void openApiDocs_arePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk());
    }

    @Test
    void transactionEvaluation_requiresIngestorRoleAndAcceptsAuthorizedBearer() throws Exception {
        String payload = """
            {
              "id":"%s",
              "accountId":"ACC-SECURITY",
              "amount":100.00,
              "currency":"ZAR",
              "category":"GROCERIES",
              "merchantName":"Market",
              "location":"Cape Town",
              "timestamp":"2026-10-19T10:00:00Z"
            }
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .authorities(() -> "ROLE_TRANSACTION_INGESTOR")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.isFraudulent").value(false));
    }

    @Test
    void alertsRequireRoleAndAccountOwnership() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-alerts/account/ACC-1001"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/fraud-alerts/account/ACC-1001")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(jwt -> jwt.claim("account_id", "ACC-1001"))
                    .authorities(() -> "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.page.totalElements").value(0))
            .andExpect(jsonPath("$.size").doesNotExist());

        mockMvc.perform(get("/api/v1/fraud-alerts/account/ACC-1001")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(jwt -> jwt.claim("account_id", "ACC-OTHER"))
                    .authorities(() -> "ROLE_ACCOUNT_HOLDER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
    }
}
