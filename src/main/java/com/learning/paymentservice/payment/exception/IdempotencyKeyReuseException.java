package com.learning.paymentservice.payment.exception;

import com.learning.paymentservice.common.exception.BusinessException;
import com.learning.paymentservice.common.exception.ErrorCategory;

/**
 * The client sent an {@code Idempotency-Key} it already used, but with a different
 * request body. Retrying is only safe with an identical body, so this is rejected
 * rather than silently returning the first payment. HTTP 422.
 */
public class IdempotencyKeyReuseException extends BusinessException {

    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    private final String idempotencyKey;

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super(CODE, ErrorCategory.UNPROCESSABLE,
                "Idempotency-Key '%s' was already used with a different request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
