package com.jlas.dictionary.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Validate chuỗi có chứa ký tự tiếng Nhật hợp lệ. */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidJapaneseText {
    String message() default "Chuỗi không chứa ký tự tiếng Nhật hợp lệ";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
