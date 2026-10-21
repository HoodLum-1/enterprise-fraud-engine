package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertControllerTest {

    @Mock
    private FraudAlertQueryService fraudAlertQueryService;

    @org.mockito.Spy
    private AccountAccessPolicy accountAccessPolicy;

    @InjectMocks
    private FraudAlertController fraudAlertController;

    @Test
    void analystCanReadPaginatedAlerts() {
        PageRequest pageable = PageRequest.of(0, 20);
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
        PageRequest pageable = PageRequest.of(0, 20);
        JwtAuthenticationToken owner = authentication("ACC-123", "ROLE_ACCOUNT_HOLDER");
        when(fraudAlertQueryService.getAlertsByAccountId("ACC-123", null, null, null, null, pageable))
            .thenReturn(Page.empty(pageable));

        assertDoesNotThrow(() -> fraudAlertController.getAlertsByAccountId(
            "ACC-123", null, null, null, null, pageable, owner));
        assertThrows(AccessDeniedException.class, () -> fraudAlertController.getAlertsByAccountId(
            "ACC-999", null, null, null, null, pageable, owner));
    }

    private JwtAuthenticationToken authentication(String accountId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("account_id", accountId)
            .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
