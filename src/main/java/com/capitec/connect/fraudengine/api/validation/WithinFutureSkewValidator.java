package com.capitec.connect.fraudengine.api.validation;

import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class WithinFutureSkewValidator implements ConstraintValidator<WithinFutureSkew, Instant> {

    private final FraudEngineProperties properties;

    /**
     * Used when the validator is not managed by Spring, for example by a plain
     * {@code Validation.buildDefaultValidatorFactory()} in a unit test.
     */
    public WithinFutureSkewValidator() {
        this(new FraudEngineProperties());
    }

    @Autowired
    public WithinFutureSkewValidator(FraudEngineProperties properties) {
        this.properties = properties != null ? properties : new FraudEngineProperties();
    }

    @Override
    public boolean isValid(Instant value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Instant now = Instant.now();
        Duration futureAllowance = properties.getEvaluation().getMaxFutureSkew();
        if (value.isAfter(now.plus(futureAllowance))) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Timestamp must not be more than " + futureAllowance.toSeconds() + " seconds in the future")
                .addConstraintViolation();
            return false;
        }

        Duration pastAllowance = properties.getEvaluation().getMaxPastSkew();
        if (pastAllowance != null && value.isBefore(now.minus(pastAllowance))) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Timestamp must not be more than " + pastAllowance.toDays() + " days in the past")
                .addConstraintViolation();
            return false;
        }
        return true;
    }
}
