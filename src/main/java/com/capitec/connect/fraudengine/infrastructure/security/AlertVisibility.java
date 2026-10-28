package com.capitec.connect.fraudengine.infrastructure.security;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shapes a fraud alert for the caller who is asking.
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

    public List<FraudAlertResponseDto> visibleTo(List<FraudAlertResponseDto> alerts,
                                                 Authentication authentication) {
        if (alerts == null || AccountAccessPolicy.isAnalyst(authentication)) {
            return alerts;
        }
        return alerts.stream().map(alert -> visibleTo(alert, authentication)).toList();
    }

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
