package com.learning.paymentservice.payment.mapper;

import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import com.learning.paymentservice.payment.domain.PaymentStatusHistory;
import com.learning.paymentservice.payment.repository.PaymentFilter;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMapperTest {

    private final PaymentMapper mapper = new PaymentMapper();

    private static CreatePaymentRequest request() {
        return new CreatePaymentRequest("  PAY-9  ", new BigDecimal("42.50"), "USD",
                PaymentMethod.CARD, " payer-1 ", "payee-1", "Books");
    }

    @Test
    void buildsANewPendingPaymentFromTheRequest() {
        Payment payment = mapper.toNewPayment(request(), "idem-9");

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getReference()).isEqualTo("PAY-9");
        assertThat(payment.getIdempotencyKey()).isEqualTo("idem-9");
        assertThat(payment.getAmount()).isEqualByComparingTo("42.50");
        assertThat(payment.getCurrency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(payment.getPayerId()).isEqualTo("payer-1");
        assertThat(payment.getPayeeId()).isEqualTo("payee-1");
        assertThat(payment.getDescription()).isEqualTo("Books");
    }

    @Test
    void treatsBlankIdempotencyKeyAndDescriptionAsAbsent() {
        var r = request();
        var noDescription = new CreatePaymentRequest(r.reference(), r.amount(), r.currency(),
                r.method(), r.payerId(), r.payeeId(), "   ");

        Payment payment = mapper.toNewPayment(noDescription, " ");

        assertThat(payment.getIdempotencyKey()).isNull();
        assertThat(payment.getDescription()).isNull();
    }

    @Test
    void mapsPaymentToResponse() {
        Payment payment = mapper.toNewPayment(request(), null);

        PaymentResponse response = mapper.toResponse(payment);

        assertThat(response.id()).isEqualTo(payment.getId());
        assertThat(response.reference()).isEqualTo("PAY-9");
        assertThat(response.amount()).isEqualByComparingTo("42.50");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.method()).isEqualTo(PaymentMethod.CARD);
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.payerId()).isEqualTo("payer-1");
        assertThat(response.payeeId()).isEqualTo("payee-1");
        assertThat(response.description()).isEqualTo("Books");
    }

    @Test
    void mapsHistoryEntryToResponse() {
        Payment payment = mapper.toNewPayment(request(), null);
        PaymentStatusHistory entry = payment.changeStatus(PaymentStatus.PROCESSING, "Sent to gateway", "ops");

        StatusHistoryResponse response = mapper.toResponse(entry);

        assertThat(response.fromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.toStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(response.reason()).isEqualTo("Sent to gateway");
        assertThat(response.changedBy()).isEqualTo("ops");
    }

    @Test
    void mapsSearchCriteriaToFilter() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-01T00:00:00Z");

        PaymentFilter filter = mapper.toFilter(new PaymentSearchCriteria(PaymentStatus.COMPLETED, " alice ", from, to));

        assertThat(filter).isEqualTo(new PaymentFilter(PaymentStatus.COMPLETED, "alice", from, to));
    }

    @Test
    void missingOrBlankCriteriaMeanNoFilter() {
        assertThat(mapper.toFilter(null)).isEqualTo(PaymentFilter.none());
        assertThat(mapper.toFilter(new PaymentSearchCriteria(null, "  ", null, null))).isEqualTo(PaymentFilter.none());
    }
}
