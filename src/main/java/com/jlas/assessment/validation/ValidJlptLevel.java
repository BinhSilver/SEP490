package com.jlas.assessment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validate giá trị JLPT level hợp lệ (N1-N5). */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidJlptLevel {
    String message() default "JLPT level không hợp lệ. Chấp nhận: N1, N2, N3, N4, N5";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
