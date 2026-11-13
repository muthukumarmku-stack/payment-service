package com.learning.paymentservice.payment.web;

import com.learning.paymentservice.common.web.PageResponse;
import com.learning.paymentservice.payment.service.PaymentService;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for payments. It only translates HTTP into service calls and
 * back: no business rules live here. Errors are turned into responses by the
 * global exception handler.
 */
@RestController
@RequestMapping(PaymentController.BASE_PATH)
public class PaymentController {

    static final String BASE_PATH = "/api/v1/payments";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** {@code POST /api/v1/payments}: 201 Created with a Location header pointing at the new payment. */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) @Size(max = 100) String idempotencyKey) {

        PaymentResponse created = paymentService.createPayment(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{paymentId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code GET /api/v1/payments/{paymentId}} */
    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable UUID paymentId) {
        return paymentService.getPayment(paymentId);
    }

    /** {@code PATCH /api/v1/payments/{paymentId}/status} */
    @PatchMapping("/{paymentId}/status")
    public PaymentResponse updateStatus(@PathVariable UUID paymentId,
                                        @Valid @RequestBody UpdatePaymentStatusRequest request) {
        return paymentService.updateStatus(paymentId, request);
    }

    /** {@code GET /api/v1/payments/{paymentId}/history}: the payment's status changes, oldest first. */
    @GetMapping("/{paymentId}/history")
    public List<StatusHistoryResponse> getStatusHistory(@PathVariable UUID paymentId) {
        return paymentService.getStatusHistory(paymentId);
    }

    /**
     * {@code GET /api/v1/payments?status=&payerId=&from=&to=&page=&size=&sort=}: the
     * payment history list. Every filter is optional; default page size is 20, max 100.
     */
    @GetMapping
    public PageResponse<PaymentResponse> searchPayments(@Valid PaymentSearchCriteria criteria,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        return paymentService.searchPayments(criteria, pageable);
    }
}
