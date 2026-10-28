package com.capitec.connect.fraudengine.infrastructure.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MinDurationValidator.class)
public @interface MinDuration {

    /** Smallest accepted length in seconds. {@code 0} means "non-negative is enough". */
    long seconds() default 1;

    String message() default "must be a duration of at least the configured number of seconds";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
