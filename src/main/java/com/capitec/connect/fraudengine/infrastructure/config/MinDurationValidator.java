package com.capitec.connect.fraudengine.infrastructure.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

public class MinDurationValidator implements ConstraintValidator<MinDuration, Duration> {

    private long minimumSeconds;

    @Override
    public void initialize(MinDuration constraintAnnotation) {
        this.minimumSeconds = constraintAnnotation.seconds();
    }

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !value.isNegative() && value.compareTo(Duration.ofSeconds(minimumSeconds)) >= 0;
    }
}
