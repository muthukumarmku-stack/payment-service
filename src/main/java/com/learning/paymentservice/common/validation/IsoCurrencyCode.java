package com.learning.paymentservice.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value must be an upper-case ISO 4217 currency code that the JDK knows,
 * such as {@code INR}, {@code USD} or {@code EUR}. {@code null} is accepted;
 * combine with {@code @NotBlank} to make the field required.
 */
@Documented
@Constraint(validatedBy = IsoCurrencyCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoCurrencyCode {

    String message() default "must be a valid ISO 4217 currency code, such as INR or USD";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
