package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Payment}. Filtered history queries go through
 * {@link JpaSpecificationExecutor} with {@link PaymentSpecifications}.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    /** Used to return the original payment when a client retries a create with the same key. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByReference(String reference);
}
