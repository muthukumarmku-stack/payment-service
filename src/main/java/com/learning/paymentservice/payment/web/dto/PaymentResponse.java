package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment as the API returns it. The JPA entity itself never leaves the service layer. */
public record PaymentResponse(
        UUID id,
        String reference,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        PaymentStatus status,
        String payerId,
        String payeeId,
        String description,
        Instant createdAt,
        Instant updatedAt) {
}
