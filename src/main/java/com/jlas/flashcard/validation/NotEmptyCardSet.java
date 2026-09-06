package com.jlas.flashcard.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validate bộ flashcard phải có ít nhất 1 card. */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmptyCardSet {
    String message() default "Bộ flashcard phải có ít nhất 1 card";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
