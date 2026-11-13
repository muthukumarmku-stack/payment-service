package com.learning.paymentservice.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a payment.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED ──► REFUNDED
 *    │             │
 *    ▼             ▼
 * CANCELLED      FAILED
 * </pre>
 *
 * The transition rules live here, next to the states, rather than in the service.
 * The switch below is exhaustive, so adding a new constant fails compilation until
 * its allowed transitions are declared.
 */
public enum PaymentStatus {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED;

    public Set<PaymentStatus> allowedNextStatuses() {
        return switch (this) {
            case PENDING -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.of(REFUNDED);
            case FAILED, CANCELLED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return target != null && allowedNextStatuses().contains(target);
    }

    public boolean isFinal() {
        return allowedNextStatuses().isEmpty();
    }
}
