package com.learning.paymentservice.common.exception;

import java.util.Map;

/**
 * Base class for expected, client-caused failures.
 *
 * <p>Each subclass declares a stable machine-readable {@link #getCode() code}, an
 * {@link ErrorCategory} and optional {@link #getDetails() details}. The global
 * handler turns any subclass into a response without knowing about it, so adding
 * a new exception needs no handler change (open/closed).
 */
public abstract class BusinessException extends RuntimeException {

    private final String code;
    private final ErrorCategory category;

    protected BusinessException(String code, ErrorCategory category, String message) {
        super(message);
        this.code = code;
        this.category = category;
    }

    /** Stable identifier clients can branch on, e.g. {@code PAYMENT_NOT_FOUND}. Never changes once published. */
    public String getCode() {
        return code;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    /** Extra fields added to the error response. Values must be safe to show to clients. */
    public Map<String, Object> getDetails() {
        return Map.of();
    }
}
