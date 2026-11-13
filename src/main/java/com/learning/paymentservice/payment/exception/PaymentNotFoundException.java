package com.learning.paymentservice.payment.exception;

import com.learning.paymentservice.common.exception.BusinessException;
import com.learning.paymentservice.common.exception.ErrorCategory;

import java.util.Map;
import java.util.UUID;

/** No payment exists with the given id. HTTP 404. */
public class PaymentNotFoundException extends BusinessException {

    public static final String CODE = "PAYMENT_NOT_FOUND";

    private final UUID paymentId;

    public PaymentNotFoundException(UUID paymentId) {
        super(CODE, ErrorCategory.NOT_FOUND, "Payment %s was not found".formatted(paymentId));
        this.paymentId = paymentId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("paymentId", paymentId);
    }
}
