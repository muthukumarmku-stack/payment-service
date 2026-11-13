package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.payment.domain.PaymentStatus;

import java.time.Instant;

/**
 * Optional filters for the payment history list. Any field may be {@code null},
 * meaning "do not filter on this".
 *
 * <p>This lives beside the repository rather than in the web layer, so the
 * repository never depends on HTTP request types. The controller's request DTO
 * maps onto it.
 *
 * @param createdFrom inclusive lower bound on {@code createdAt}
 * @param createdTo   exclusive upper bound on {@code createdAt}
 */
public record PaymentFilter(PaymentStatus status, String payerId, Instant createdFrom, Instant createdTo) {

    public static PaymentFilter none() {
        return new PaymentFilter(null, null, null, null);
    }
}
