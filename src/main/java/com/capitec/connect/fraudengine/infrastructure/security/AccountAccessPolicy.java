package com.capitec.connect.fraudengine.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public class AccountAccessPolicy {

    private static final String ANALYST_ROLE = "ROLE_FRAUD_ANALYST";
    private static final String ACCOUNT_ID_CLAIM = "account_id";
    private static final String ACCOUNT_ID_CLAIM_ALTERNATIVE = "accountId";

    public void verifyAccess(String accountId, Authentication authentication,
                             Supplier<RuntimeException> notFound) {
        if (authentication == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        if (isAnalyst(authentication)) {
            return;
        }
        if (Objects.equals(accountId, accountIdOf(authentication))) {
            return;
        }
        throw notFound.get();
    }

    public static boolean isAnalyst(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> ANALYST_ROLE.equals(authority.getAuthority()));
    }

    public static String accountIdOf(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return null;
        }
        String claim = jwtAuthentication.getToken().getClaimAsString(ACCOUNT_ID_CLAIM);
        if (claim == null) {
            claim = jwtAuthentication.getToken().getClaimAsString(ACCOUNT_ID_CLAIM_ALTERNATIVE);
        }
        return claim;
    }
}
