package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.payment.domain.PaymentStatus;

import java.time.Instant;

/**
 * One entry in a payment's status history.
 *
 * @param fromStatus {@code null} for the entry recording creation
 */
public record StatusHistoryResponse(
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String reason,
        String changedBy,
        Instant changedAt) {
}
