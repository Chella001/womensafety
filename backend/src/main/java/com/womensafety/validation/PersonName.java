package com.womensafety.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A person's name: after trimming it must have {@code min}..{@code max} characters and contain only letters,
 * marks, spaces, dots, apostrophes and hyphens. {@code null} is valid; combine with {@code @NotNull} to require it.
 */
@Documented
@Constraint(validatedBy = PersonNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {

    String message() default "Name must be 2 to 60 characters and contain only letters, spaces, . ' and -";

    int min() default 2;

    int max() default 60;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
