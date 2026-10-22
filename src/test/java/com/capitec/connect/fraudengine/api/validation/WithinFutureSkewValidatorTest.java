package com.capitec.connect.fraudengine.api.validation;

import com.capitec.connect.fraudengine.infrastructure.config.FraudEngineProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithinFutureSkewValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void theDefaultAllowanceIsFiveMinutes() {
        assertTrue(isValid(Instant.now().plusSeconds(60)));
        assertFalse(isValid(Instant.now().plus(Duration.ofMinutes(10))));
    }

    @Test
    void theAllowanceCanBeConfigured() {
        WithinFutureSkewValidator configured = new WithinFutureSkewValidator(propertiesWith(Duration.ofMinutes(30)));

        assertTrue(configured.isValid(Instant.now().plus(Duration.ofMinutes(20)), context()));
        assertFalse(configured.isValid(Instant.now().plus(Duration.ofMinutes(40)), context()));
    }

    private ConstraintValidatorContext context() {
        ConstraintValidatorContext context = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintValidatorContext.ConstraintViolationBuilder builder =
            Mockito.mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        Mockito.when(builder.addConstraintViolation()).thenReturn(context);
        Mockito.when(context.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        return context;
    }

    private boolean isValid(Instant timestamp) {
        Set<ConstraintViolation<WithinFutureSkewHolder>> violations = validator.validate(
            new WithinFutureSkewHolder(timestamp));
        return violations.isEmpty();
    }

    private FraudEngineProperties propertiesWith(Duration skew) {
        FraudEngineProperties properties = new FraudEngineProperties();
        properties.getEvaluation().setMaxFutureSkew(skew);
        return properties;
    }

    record WithinFutureSkewHolder(@WithinFutureSkew Instant timestamp) {
    }
}
