package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.payment.domain.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/payments/{id}/status}. Whether the move is allowed
 * is a business rule, checked by the domain, not here.
 */
public record UpdatePaymentStatusRequest(

        @NotNull
        PaymentStatus status,

        @Size(max = 255)
        String reason) {
}
