package com.capitec.connect.fraudengine.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter();

    @Test
    void convert_extractsRealmClientAndScopeRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("user-123")
            .claim("preferred_username", "fraud-analyst")
            .claim("realm_access", Map.of("roles", List.of("FRAUD_ANALYST", "ROLE_AUDITOR")))
            .claim("resource_access", Map.of(
                "fraud-engine-client", Map.of("roles", List.of("CASE_READER"))
            ))
            .claim("scope", "openid report:fraud")
            .claim("scp", List.of("case:read"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

        JwtAuthenticationToken authentication = (JwtAuthenticationToken) converter.convert(jwt);

        assertEquals("fraud-analyst", authentication.getName());
        assertEquals(Set.of(
            "ROLE_FRAUD_ANALYST",
            "ROLE_AUDITOR",
            "ROLE_CASE_READER",
            "ROLE_openid",
            "ROLE_report:fraud",
            "ROLE_case:read"
        ), authentication.getAuthorities().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
    }
}
