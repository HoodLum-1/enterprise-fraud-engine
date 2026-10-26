package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.exception.ResourceNotFoundException;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.capitec.connect.fraudengine.infrastructure.security.AlertVisibility;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertControllerTest {

    @Mock
    private FraudAlertQueryService fraudAlertQueryService;

    @org.mockito.Spy
    private AccountAccessPolicy accountAccessPolicy;

    @org.mockito.Spy
    private AlertVisibility alertVisibility = new AlertVisibility();

    @InjectMocks
    private FraudAlertController fraudAlertController;

    @Test
    void analystCanReadPaginatedAlerts() {
        Pageable pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken analyst = authentication("other-account", "ROLE_FRAUD_ANALYST");
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123", null, null, null, null, pageable))
            .thenReturn(Page.empty(pageable));

        var response = fraudAlertController.getAlertsByAccountId(
            "ACC-123", null, null, null, null, pageable, analyst);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(fraudAlertQueryService).getAlertsByAccountId("ACC-123", null, null, null, null, pageable);
    }

    @Test
    void accountHolderMayReadOnlyOwnAccountAlerts() {
        Pageable pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken owner = authentication("ACC-123", "ROLE_ACCOUNT_HOLDER");
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123", null, null, null, null, pageable))
            .thenReturn(Page.empty(pageable));

        assertDoesNotThrow(() -> fraudAlertController.getAlertsByAccountId(
            "ACC-123", null, null, null, null, pageable, owner));
    }

    @Test
    void accountHolderIsToldTheSameStoryAsAMissingAccount() {
        Pageable pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken owner = authentication("ACC-123", "ROLE_ACCOUNT_HOLDER");

        // Somebody else's account, and an account that does not exist, must be indistinguishable,
        // otherwise the endpoint is an existence oracle for account ids.
        RuntimeException otherAccount = assertThrows(ResourceNotFoundException.class,
            () -> fraudAlertController.getAlertsByAccountId(
                "ACC-999", null, null, null, null, pageable, owner));
        RuntimeException unknownAccount = assertThrows(ResourceNotFoundException.class,
            () -> fraudAlertController.getAlertsByAccountId(
                "ACC-000", null, null, null, null, pageable, owner));

        assertEquals(unknownAccount.getMessage(), otherAccount.getMessage());
        verifyNoInteractions(fraudAlertQueryService);
    }

    @Test
    void accountHolderSeesSeverityButNotTheRuleOrItsScore() {
        Pageable pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken owner = authentication("ACC-123", "ROLE_ACCOUNT_HOLDER");
        FraudAlertResponseDto alert = new FraudAlertResponseDto(
            UUID.randomUUID(), UUID.randomUUID(), "ACC-123",
            "HighAmountRule", 45, Severity.HIGH,
            "Amount 60000.00 exceeds HIGH threshold of 50000.00", Instant.now());
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123", null, null, null, null, pageable))
            .thenReturn(new PageImpl<>(List.of(alert), pageable, 1));

        var response = fraudAlertController.getAlertsByAccountId(
            "ACC-123", null, null, null, null, pageable, owner);

        FraudAlertResponseDto visible = response.getBody().getContent().getFirst();
        assertNull(visible.ruleName(), "the rule name tells a customer how the model is weighted");
        assertNull(visible.riskScore(), "the points tell a customer how far from the threshold they are");
        assertEquals(alert.severity(), visible.severity());
        assertEquals(alert.transactionId(), visible.transactionId());
        assertNotNull(visible.reason());
    }

    @Test
    void analystSeesTheRuleAndTheScoreTheHolderDoesNot() {
        Pageable pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken analyst = authentication("other-account", "ROLE_FRAUD_ANALYST");
        FraudAlertResponseDto alert = new FraudAlertResponseDto(
            UUID.randomUUID(), UUID.randomUUID(), "ACC-123",
            "HighAmountRule", 45, Severity.HIGH,
            "Amount 60000.00 exceeds HIGH threshold of 50000.00", Instant.now());
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123", null, null, null, null, pageable))
            .thenReturn(new PageImpl<>(List.of(alert), pageable, 1));

        var response = fraudAlertController.getAlertsByAccountId(
            "ACC-123", null, null, null, null, pageable, analyst);

        FraudAlertResponseDto visible = response.getBody().getContent().getFirst();
        assertEquals("HighAmountRule", visible.ruleName());
        assertEquals(45, visible.riskScore());
        assertEquals("Amount 60000.00 exceeds HIGH threshold of 50000.00", visible.reason());
    }

    private JwtAuthenticationToken authentication(String accountId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("account_id", accountId)
            .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
