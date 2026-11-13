package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.common.validation.IsoCurrencyCode;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/payments}. The optional idempotency key travels in
 * the {@code Idempotency-Key} header, not here.
 *
 * <p>The limits match the database columns, so a request that passes validation
 * can always be stored.
 */
public record CreatePaymentRequest(

        @NotBlank @Size(max = 64)
        String reference,

        @NotNull @Positive @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank @IsoCurrencyCode
        String currency,

        @NotNull
        PaymentMethod method,

        @NotBlank @Size(max = 64)
        String payerId,

        @NotBlank @Size(max = 64)
        String payeeId,

        @Size(max = 255)
        String description) {

    /** Reported as a 400 field error; the domain also refuses it, as a last line of defence. */
    @AssertTrue(message = "payeeId must be different from payerId")
    public boolean isPayeeDifferentFromPayer() {
        return payerId == null || payeeId == null || !payerId.trim().equals(payeeId.trim());
    }
}
