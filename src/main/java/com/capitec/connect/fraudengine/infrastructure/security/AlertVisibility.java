package com.capitec.connect.fraudengine.infrastructure.security;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shapes a fraud alert for the caller who is asking.
 *
 * <p>An analyst needs the rule name, the points it contributed and the wording the rule produced,
 * because those are what they investigate with. An account holder needs to know that a transaction
 * of theirs was flagged, at what severity, and when, so they can dispute it. They do not need
 * {@code HighAmountRule scored 45 points above the 50000.00 threshold}, which tells them how to
 * stay under it and tells anyone replaying responses how the model is weighted.
 *
 * <p>The severity is kept because it is the one part of the alert a customer can act on, and the
 * ids are kept because they are how a customer refers to their own transaction.
 */
@Component
public class AlertVisibility {

    /**
     * The wording an account holder sees in place of the rule's own reason.
     *
     * <p>Deliberately fixed and deliberately vague. A per-rule customer message would either
     * restate the rule or have to be maintained as a second copy of every rule's intent.
     */
    private static final String CUSTOMER_REASON =
        "This transaction was flagged for review by our fraud monitoring team.";

    /** The alert as the given caller is allowed to see it. */
    public FraudAlertResponseDto visibleTo(FraudAlertResponseDto alert, Authentication authentication) {
        if (alert == null || AccountAccessPolicy.isAnalyst(authentication)) {
            return alert;
        }
        return new FraudAlertResponseDto(
            alert.id(),
            alert.transactionId(),
            alert.accountId(),
            null,
            null,
            alert.severity(),
            CUSTOMER_REASON,
            alert.flaggedAt()
        );
    }

    /** Every alert in the list as the given caller is allowed to see them. */
    public List<FraudAlertResponseDto> visibleTo(List<FraudAlertResponseDto> alerts,
                                                 Authentication authentication) {
        if (alerts == null || AccountAccessPolicy.isAnalyst(authentication)) {
            return alerts;
        }
        return alerts.stream().map(alert -> visibleTo(alert, authentication)).toList();
    }

    /**
     * Transaction details with the embedded alerts shaped for the caller.
     *
     * <p>The totals are recomputed from what the caller may actually see. Leaving a risk score of
     * 85 on the parent while redacting the per-alert scores would give away the number anyway.
     */
    public TransactionDetailsResponseDto visibleTo(TransactionDetailsResponseDto details,
                                                   Authentication authentication) {
        if (details == null || AccountAccessPolicy.isAnalyst(authentication)) {
            return details;
        }
        List<FraudAlertResponseDto> alerts = visibleTo(details.alerts(), authentication);
        return new TransactionDetailsResponseDto(
            details.transactionId(),
            details.accountId(),
            details.amount(),
            details.currency(),
            details.category(),
            details.merchantName(),
            details.location(),
            details.timestamp(),
            details.isFraudulent(),
            null,
            details.overallSeverity(),
            alerts
        );
    }
}
