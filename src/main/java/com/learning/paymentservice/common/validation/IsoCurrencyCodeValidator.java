package com.learning.paymentservice.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class IsoCurrencyCodeValidator implements ConstraintValidator<IsoCurrencyCode, String> {

    private static final Pattern THREE_UPPER_CASE_LETTERS = Pattern.compile("[A-Z]{3}");

    private static final Set<String> KNOWN_CODES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return THREE_UPPER_CASE_LETTERS.matcher(value).matches() && KNOWN_CODES.contains(value);
    }
}
