package com.capitec.connect.fraudengine.infrastructure.security;

import com.capitec.connect.fraudengine.api.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountAccessPolicyTest {

    private final AccountAccessPolicy policy = new AccountAccessPolicy();

    @Test
    void anAnalystReadsEveryAccount() {
        assertDoesNotThrow(() -> policy.verifyAccess("ACC-1", analyst(), ResourceNotFoundException::new));
        assertDoesNotThrow(() -> policy.verifyAccess("SOMEONE-ELSE", analyst(), ResourceNotFoundException::new));
    }

    @Test
    void anAccountHolderReadsTheirOwnAccount() {
        assertDoesNotThrow(() -> policy.verifyAccess("ACC-1", holder("ACC-1"), ResourceNotFoundException::new));
    }

    @Test
    void anAccountHolderIsRefusedEveryOtherAccount() {
        assertThrows(ResourceNotFoundException.class,
            () -> policy.verifyAccess("ACC-2", holder("ACC-1"), ResourceNotFoundException::new));
    }

    @Test
    void aRefusalIsTheSameAnswerAsAMissingResource() {
        ResourceNotFoundException expected = new ResourceNotFoundException();

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
            () -> policy.verifyAccess("ACC-2", holder("ACC-1"), () -> expected));

        assertSame(expected, thrown);
        assertEquals(new ResourceNotFoundException().getMessage(), thrown.getMessage());
    }

    @Test
    void theAlternativeAccountIdClaimIsAccepted() {
        // Keycloak and the local issuer name the same value differently.
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("accountId", "ACC-1").build();
        JwtAuthenticationToken holder = new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));

        assertDoesNotThrow(() -> policy.verifyAccess("ACC-1", holder, ResourceNotFoundException::new));
    }

    @Test
    void aTokenThatNamesNoAccountReadsNothing() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "a-user").build();
        JwtAuthenticationToken holder = new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));

        assertThrows(ResourceNotFoundException.class,
            () -> policy.verifyAccess("ACC-1", holder, ResourceNotFoundException::new));
    }

    @Test
    void anUnauthenticatedCallIsRefusedOutright() {
        assertThrows(AccessDeniedException.class,
            () -> policy.verifyAccess("ACC-1", null, ResourceNotFoundException::new));
    }

    @Test
    void theAccountIdIsReadOnlyFromAJwt() {
        assertNull(AccountAccessPolicy.accountIdOf(new TestingAuthenticationToken(
            "user", "password", List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")))));
    }

    private JwtAuthenticationToken analyst() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("account_id", "THEIR-OWN").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_FRAUD_ANALYST")));
    }

    private JwtAuthenticationToken holder(String accountId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("account_id", accountId).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));
    }
}
