package com.capitec.connect.fraudengine.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a timestamp that is further in the future than the configured clock skew allowance.
 *
 * <p>Transactions are produced by other systems, so their clocks are never exactly in step with
 * the service's. A small allowance absorbs that, while a timestamp that is far in the future is
 * rejected, because it would sit outside every history window and make the evaluation meaningless.
 */
@Documented
@Constraint(validatedBy = WithinFutureSkewValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface WithinFutureSkew {

    String message() default "Timestamp is too far in the future";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
