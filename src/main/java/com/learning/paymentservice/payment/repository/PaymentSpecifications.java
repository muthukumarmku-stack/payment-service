package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Small, composable query conditions for {@link Payment}. Each one does a single
 * thing; {@link #matching} combines only the filters that were actually supplied.
 */
public final class PaymentSpecifications {

    private static final String STATUS = "status";
    private static final String PAYER_ID = "payerId";
    private static final String CREATED_AT = "createdAt";

    private PaymentSpecifications() {
    }

    public static Specification<Payment> matching(PaymentFilter filter) {
        List<Specification<Payment>> conditions = new ArrayList<>();
        if (filter.status() != null) {
            conditions.add(hasStatus(filter.status()));
        }
        if (filter.payerId() != null && !filter.payerId().isBlank()) {
            conditions.add(hasPayerId(filter.payerId()));
        }
        if (filter.createdFrom() != null) {
            conditions.add(createdAtOrAfter(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            conditions.add(createdBefore(filter.createdTo()));
        }
        return Specification.allOf(conditions);
    }

    public static Specification<Payment> hasStatus(PaymentStatus status) {
        return (root, query, cb) -> cb.equal(root.get(STATUS), status);
    }

    public static Specification<Payment> hasPayerId(String payerId) {
        return (root, query, cb) -> cb.equal(root.get(PAYER_ID), payerId);
    }

    public static Specification<Payment> createdAtOrAfter(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get(CREATED_AT), from);
    }

    public static Specification<Payment> createdBefore(Instant to) {
        return (root, query, cb) -> cb.lessThan(root.get(CREATED_AT), to);
    }
}
