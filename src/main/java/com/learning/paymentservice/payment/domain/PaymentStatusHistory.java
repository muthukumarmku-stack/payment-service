package com.learning.paymentservice.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable row in a payment's audit trail.
 *
 * <p>It refers to its payment by id rather than a {@code @ManyToOne}: the history
 * is only ever read by payment id, and this keeps the two aggregates separate
 * (no lazy-loading surprises).
 */
@Entity
@Table(name = "payment_status_history")
@EntityListeners(AuditingEntityListener.class)
public class PaymentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** {@code null} for the entry written when the payment is created. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private PaymentStatus toStatus;

    @Column(length = 255, updatable = false)
    private String reason;

    @Column(name = "changed_by", length = 64, updatable = false)
    private String changedBy;

    @CreatedDate
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    /** Required by JPA. */
    protected PaymentStatusHistory() {
    }

    private PaymentStatusHistory(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
                                 String reason, String changedBy) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        this.reason = reason;
        this.changedBy = changedBy;
    }

    /** Package-private: only {@link Payment} creates history entries, so they always match a real change. */
    static PaymentStatusHistory record(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
                                       String reason, String changedBy) {
        return new PaymentStatusHistory(paymentId, fromStatus, toStatus, reason, changedBy);
    }

    public Long getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof PaymentStatusHistory that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "PaymentStatusHistory[paymentId=%s, %s -> %s]".formatted(paymentId, fromStatus, toStatus);
    }
}
