package com.learning.paymentservice.payment.exception;

import com.learning.paymentservice.common.exception.BusinessException;
import com.learning.paymentservice.common.exception.ErrorCategory;

import java.util.Map;

/** Another payment already uses this reference. HTTP 409. */
public class DuplicatePaymentReferenceException extends BusinessException {

    public static final String CODE = "DUPLICATE_REFERENCE";

    private final String reference;

    public DuplicatePaymentReferenceException(String reference) {
        super(CODE, ErrorCategory.CONFLICT, "A payment with reference '%s' already exists".formatted(reference));
        this.reference = reference;
    }

    public String getReference() {
        return reference;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("reference", reference);
    }
}
