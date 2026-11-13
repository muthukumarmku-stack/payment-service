package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.payment.domain.PaymentStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Query parameters of {@code GET /api/v1/payments}; all optional.
 * Paging ({@code page}, {@code size}, {@code sort}) is bound separately as a {@code Pageable}.
 *
 * @param from inclusive, ISO-8601 instant such as {@code 2026-11-01T00:00:00Z}
 * @param to   exclusive
 */
public record PaymentSearchCriteria(
        PaymentStatus status,
        @Size(max = 64) String payerId,
        Instant from,
        Instant to) {

    @AssertTrue(message = "'from' must be before 'to'")
    public boolean isDateRangeValid() {
        return from == null || to == null || from.isBefore(to);
    }
}
