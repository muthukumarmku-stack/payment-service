package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.payment.domain.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Persistence for the status audit trail. Rows are only ever inserted and read. */
public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {

    /**
     * The audit trail of one payment, oldest first. The id is the tie-breaker
     * for rows written within the same clock tick.
     */
    List<PaymentStatusHistory> findByPaymentIdOrderByChangedAtAscIdAsc(UUID paymentId);
}
