package com.learning.paymentservice.payment.mapper;

import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentStatusHistory;
import com.learning.paymentservice.payment.repository.PaymentFilter;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import org.springframework.stereotype.Component;

import java.util.Currency;

/**
 * Converts between API DTOs and domain objects, so neither the controller nor
 * the service needs to know how the other side is shaped.
 *
 * <p>Written by hand rather than generated (for example with MapStruct), so
 * every conversion is visible in one place.
 */
@Component
public class PaymentMapper {

    /**
     * Builds a new domain payment. Assumes the request has already passed Bean
     * Validation; the domain still guards its own rules.
     */
    public Payment toNewPayment(CreatePaymentRequest request, String idempotencyKey) {
        return Payment.create(
                request.reference().trim(),
                blankToNull(idempotencyKey),
                request.amount(),
                Currency.getInstance(request.currency()),
                request.method(),
                request.payerId().trim(),
                request.payeeId().trim(),
                blankToNull(request.description()));
    }

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getReference(),
                payment.getAmount(),
                payment.getCurrency().getCurrencyCode(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getPayerId(),
                payment.getPayeeId(),
                payment.getDescription(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    public StatusHistoryResponse toResponse(PaymentStatusHistory entry) {
        return new StatusHistoryResponse(
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getReason(),
                entry.getChangedBy(),
                entry.getChangedAt());
    }

    public PaymentFilter toFilter(PaymentSearchCriteria criteria) {
        if (criteria == null) {
            return PaymentFilter.none();
        }
        return new PaymentFilter(
                criteria.status(),
                blankToNull(criteria.payerId()),
                criteria.from(),
                criteria.to());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
