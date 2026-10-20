package com.capitec.connect.fraudengine.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AccountAccessPolicy {

    public void verifyAccess(String accountId, Authentication authentication) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        boolean analyst = authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_FRAUD_ANALYST".equals(authority.getAuthority()));
        if (analyst) {
            return;
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String claim = jwtAuthentication.getToken().getClaimAsString("account_id");
            if (claim == null) {
                claim = jwtAuthentication.getToken().getClaimAsString("accountId");
            }
            if (Objects.equals(accountId, claim)) {
                return;
            }
        }
        throw new AccessDeniedException("The authenticated user is not authorized to access this account");
    }
}
