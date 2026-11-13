package com.learning.paymentservice.payment.service;

import com.learning.paymentservice.common.web.PageResponse;
import com.learning.paymentservice.payment.exception.DuplicatePaymentReferenceException;
import com.learning.paymentservice.payment.exception.IdempotencyKeyReuseException;
import com.learning.paymentservice.payment.exception.InvalidStatusTransitionException;
import com.learning.paymentservice.payment.exception.PaymentNotFoundException;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * The payment use cases. The controller depends on this interface, not on the
 * implementation, so it can be tested with a mock and the implementation can
 * change freely.
 */
public interface PaymentService {

    /**
     * Creates a payment in PENDING and records the first history entry.
     *
     * @param idempotencyKey optional; when a payment with this key already exists and the
     *                       request is identical, that payment is returned instead of a new one
     * @throws DuplicatePaymentReferenceException if the reference is already used
     * @throws IdempotencyKeyReuseException       if the key was used with a different request
     */
    PaymentResponse createPayment(CreatePaymentRequest request, String idempotencyKey);

    /** @throws PaymentNotFoundException if no payment has this id */
    PaymentResponse getPayment(UUID paymentId);

    /**
     * Moves a payment to a new status and records the change in its history.
     *
     * @throws PaymentNotFoundException         if no payment has this id
     * @throws InvalidStatusTransitionException if the lifecycle does not allow the move
     */
    PaymentResponse updateStatus(UUID paymentId, UpdatePaymentStatusRequest request);

    /**
     * The status changes of one payment, oldest first.
     *
     * @throws PaymentNotFoundException if no payment has this id
     */
    List<StatusHistoryResponse> getStatusHistory(UUID paymentId);

    /** Payments matching the criteria; newest first unless {@code pageable} specifies a sort. */
    PageResponse<PaymentResponse> searchPayments(PaymentSearchCriteria criteria, Pageable pageable);
}
