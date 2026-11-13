package com.learning.paymentservice.payment.domain;

import com.learning.paymentservice.payment.exception.InvalidStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment from a payer to a payee.
 *
 * <p>State changes only go through {@link #create} and {@link #changeStatus}, so
 * the entity can never hold an invalid amount or skip a lifecycle rule. There are
 * no setters.
 */
@Entity
@Table(name = "payments")
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "payer_id", nullable = false, length = 64)
    private String payerId;

    @Column(name = "payee_id", nullable = false, length = 64)
    private String payeeId;

    @Column(length = 255)
    private String description;

    /** Optimistic locking: concurrent status updates fail instead of overwriting each other. */
    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Application code uses {@link #create}. */
    protected Payment() {
    }

    private Payment(String reference, String idempotencyKey, BigDecimal amount, Currency currency,
                    PaymentMethod method, String payerId, String payeeId, String description) {
        this.id = UUID.randomUUID();
        this.reference = requireText(reference, "reference");
        this.idempotencyKey = idempotencyKey;
        this.amount = requirePositive(amount);
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.payerId = requireText(payerId, "payerId");
        this.payeeId = requireText(payeeId, "payeeId");
        this.description = description;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Creates a new payment in {@link PaymentStatus#PENDING}.
     *
     * @param idempotencyKey optional client-supplied key; {@code null} when not sent
     * @param description    optional free text; {@code null} allowed
     */
    public static Payment create(String reference, String idempotencyKey, BigDecimal amount, Currency currency,
                                 PaymentMethod method, String payerId, String payeeId, String description) {
        if (Objects.equals(payerId, payeeId) && payerId != null) {
            throw new IllegalArgumentException("payerId and payeeId must be different");
        }
        return new Payment(reference, idempotencyKey, amount, currency, method, payerId, payeeId, description);
    }

    /** The audit entry recording that this payment was created. Save it together with the payment. */
    public PaymentStatusHistory initialHistoryEntry(String changedBy) {
        return PaymentStatusHistory.record(id, null, status, "Payment created", changedBy);
    }

    /**
     * Moves the payment to {@code target} if the lifecycle allows it.
     *
     * @return the audit entry for this change; the caller saves it in the same transaction
     * @throws InvalidStatusTransitionException if the move is not allowed
     */
    public PaymentStatusHistory changeStatus(PaymentStatus target, String reason, String changedBy) {
        Objects.requireNonNull(target, "target status must not be null");
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, status, target);
        }
        PaymentStatus previous = status;
        status = target;
        return PaymentStatusHistory.record(id, previous, target, reason, changedBy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return amount;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getPayerId() {
        return payerId;
    }

    public String getPayeeId() {
        return payeeId;
    }

    public String getDescription() {
        return description;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Identity is the id, which is assigned at creation and never changes. */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Payment that && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payment[id=%s, reference=%s, status=%s]".formatted(id, reference, status);
    }
}
