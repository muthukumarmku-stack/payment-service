package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.TestcontainersConfiguration;
import com.learning.paymentservice.common.config.JpaAuditingConfig;
import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import com.learning.paymentservice.payment.domain.PaymentStatusHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class PaymentStatusHistoryRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentStatusHistoryRepository historyRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Payment savedPayment(String reference) {
        return paymentRepository.saveAndFlush(Payment.create(reference, null, BigDecimal.TEN,
                Currency.getInstance("EUR"), PaymentMethod.BANK_TRANSFER, "payer-1", "payee-1", null));
    }

    @Test
    void returnsOnePaymentsTrailOldestFirst() {
        Payment payment = savedPayment("PAY-H1");
        historyRepository.save(payment.initialHistoryEntry("api"));
        historyRepository.save(payment.changeStatus(PaymentStatus.PROCESSING, "Sent to gateway", "ops"));
        historyRepository.save(payment.changeStatus(PaymentStatus.COMPLETED, "Gateway confirmed", "ops"));
        historyRepository.flush();
        entityManager.clear();

        List<PaymentStatusHistory> trail = historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(payment.getId());

        assertThat(trail).extracting(PaymentStatusHistory::getToStatus)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.PROCESSING, PaymentStatus.COMPLETED);
        assertThat(trail.getFirst().getFromStatus()).isNull();
        assertThat(trail.get(1).getReason()).isEqualTo("Sent to gateway");
        assertThat(trail).allSatisfy(entry -> {
            assertThat(entry.getId()).isNotNull();
            assertThat(entry.getChangedAt()).isNotNull();
        });
    }

    @Test
    void doesNotMixUpDifferentPayments() {
        Payment first = savedPayment("PAY-H2");
        Payment second = savedPayment("PAY-H3");
        historyRepository.saveAllAndFlush(List.of(first.initialHistoryEntry("api"), second.initialHistoryEntry("api")));

        assertThat(historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(first.getId()))
                .singleElement()
                .extracting(PaymentStatusHistory::getPaymentId)
                .isEqualTo(first.getId());
    }

    @Test
    void returnsEmptyListForUnknownPayment() {
        assertThat(historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(UUID.randomUUID())).isEmpty();
    }

    @Test
    void foreignKeyRejectsHistoryForAPaymentThatDoesNotExist() {
        Payment neverSaved = Payment.create("PAY-GHOST", null, BigDecimal.ONE, Currency.getInstance("EUR"),
                PaymentMethod.CARD, "payer-1", "payee-1", null);

        assertThatThrownBy(() -> historyRepository.saveAndFlush(neverSaved.initialHistoryEntry("api")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
