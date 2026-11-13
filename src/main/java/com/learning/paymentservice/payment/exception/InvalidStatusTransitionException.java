package com.learning.paymentservice.payment.exception;

import com.learning.paymentservice.common.exception.BusinessException;
import com.learning.paymentservice.common.exception.ErrorCategory;
import com.learning.paymentservice.payment.domain.PaymentStatus;

import java.util.Map;
import java.util.UUID;

/**
 * Thrown when a status change is not allowed by {@link PaymentStatus#canTransitionTo}.
 * HTTP 409; the response lists the statuses that would have been allowed.
 */
public class InvalidStatusTransitionException extends BusinessException {

    public static final String CODE = "INVALID_STATUS_TRANSITION";

    private final UUID paymentId;
    private final PaymentStatus currentStatus;
    private final PaymentStatus requestedStatus;

    public InvalidStatusTransitionException(UUID paymentId, PaymentStatus currentStatus, PaymentStatus requestedStatus) {
        super(CODE, ErrorCategory.CONFLICT,
                "Payment %s cannot move from %s to %s".formatted(paymentId, currentStatus, requestedStatus));
        this.paymentId = paymentId;
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getCurrentStatus() {
        return currentStatus;
    }

    public PaymentStatus getRequestedStatus() {
        return requestedStatus;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of(
                "paymentId", paymentId,
                "currentStatus", currentStatus,
                "requestedStatus", requestedStatus,
                "allowedStatuses", currentStatus.allowedNextStatuses().stream().sorted().toList());
    }
}
