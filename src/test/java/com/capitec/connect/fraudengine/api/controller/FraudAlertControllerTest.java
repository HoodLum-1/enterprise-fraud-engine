package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.exception.GlobalExceptionHandler;
import com.capitec.connect.fraudengine.domain.model.FraudAlert;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import com.capitec.connect.fraudengine.util.FraudTestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FraudAlertQueryService fraudAlertQueryService;

    @InjectMocks
    private FraudAlertController fraudAlertController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(fraudAlertController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void testGetAlertsByAccountId_ReturnsAlertsList() throws Exception {
        FraudAlert alert = FraudTestDataFactory.fraudAlert()
            .accountId("ACC-123")
            .ruleName("TransactionVelocityRule")
            .riskScore(50)
            .severity(Severity.CRITICAL)
            .reason("High velocity")
            .build();
        FraudAlertResponseDto dto = new FraudAlertMapper().toResponseDto(alert);

        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123"))
            .thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/fraud-alerts/account/{accountId}", "ACC-123")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(alert.id().toString()))
            .andExpect(jsonPath("$[0].transactionId").value(alert.transactionId().toString()))
            .andExpect(jsonPath("$[0].accountId").value("ACC-123"))
            .andExpect(jsonPath("$[0].ruleName").value("TransactionVelocityRule"))
            .andExpect(jsonPath("$[0].riskScore").value(50))
            .andExpect(jsonPath("$[0].severity").value("CRITICAL"))
            .andExpect(jsonPath("$[0].reason").value("High velocity"));
    }

    @Test
    void testGetAlertsByAccountId_WhenEmpty_ReturnsEmptyList() throws Exception {
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-UNKNOWN"))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/fraud-alerts/account/{accountId}", "ACC-UNKNOWN")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }
}
