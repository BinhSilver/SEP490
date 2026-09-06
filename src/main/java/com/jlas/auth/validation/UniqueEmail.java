package com.jlas.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Annotation validate email hợp lệ và chưa tồn tại trong hệ thống.
 * TODO: Triển khai {@link UniqueEmailValidator}.
 */
@Documented
@Constraint(validatedBy = {}) // TODO: validatedBy = UniqueEmailValidator.class
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueEmail {

    String message() default "Email đã được sử dụng";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
