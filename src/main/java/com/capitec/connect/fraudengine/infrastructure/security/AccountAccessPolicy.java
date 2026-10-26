package com.capitec.connect.fraudengine.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Decides which authenticated caller may read which account.
 *
 * <p>Only {@code FRAUD_ANALYST} reads every account. Everyone else reads the single account named
 * by their own {@code account_id} claim, so a caller cannot read a neighbour's account by asking
 * for its id.
 *
 * <p>A refusal is reported with the not-found the caller would have received for a resource that
 * does not exist, rather than as a 403. A 403 would confirm that the transaction exists and merely
 * belongs to somebody else, which turns the endpoint into an existence oracle for anyone holding a
 * valid token for some other account.
 */
@Component
public class AccountAccessPolicy {

    private static final String ANALYST_ROLE = "ROLE_FRAUD_ANALYST";
    private static final String ACCOUNT_ID_CLAIM = "account_id";
    private static final String ACCOUNT_ID_CLAIM_ALTERNATIVE = "accountId";

    /**
     * Verifies the caller may read {@code accountId}.
     *
     * @param accountId the account being read
     * @param authentication the caller, may be null when the request was not authenticated
     * @param notFound the exception to raise instead of a 403 when the caller is authenticated but
     *                 is not the account holder; passing the same instance the missing case raises
     *                 keeps the two answers byte-for-byte identical
     */
    public void verifyAccess(String accountId, Authentication authentication,
                             Supplier<RuntimeException> notFound) {
        if (authentication == null) {
            // No caller identity at all: there is no account to compare against, and this is not a
            // resource the caller was asking about, so a plain refusal is the honest answer.
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

    /** Whether the caller reads every account, which only {@code FRAUD_ANALYST} does. */
    public static boolean isAnalyst(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> ANALYST_ROLE.equals(authority.getAuthority()));
    }

    /** The single account the caller is allowed to read, or null when the token names none. */
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
