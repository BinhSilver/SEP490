package com.jlas.moderation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validate số credit phải dương. */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCreditAmount {
    String message() default "Số credit phải là số dương";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
