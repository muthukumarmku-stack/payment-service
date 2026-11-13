package com.learning.paymentservice.payment.exception;

import com.learning.paymentservice.common.exception.BusinessException;
import com.learning.paymentservice.common.exception.ErrorCategory;

import java.util.Collection;
import java.util.Map;

/** {@code ?sort=} named a field the payment list cannot be sorted by. HTTP 400. */
public class UnsupportedSortPropertyException extends BusinessException {

    public static final String CODE = "UNSUPPORTED_SORT_PROPERTY";

    private final String property;
    private final Collection<String> supported;

    public UnsupportedSortPropertyException(String property, Collection<String> supported) {
        super(CODE, ErrorCategory.INVALID_REQUEST, "Cannot sort payments by '%s'".formatted(property));
        this.property = property;
        this.supported = supported;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("property", property, "supportedProperties", supported.stream().sorted().toList());
    }
}
