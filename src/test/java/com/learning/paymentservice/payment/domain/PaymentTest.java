package com.learning.paymentservice.payment.domain;

import com.learning.paymentservice.payment.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Currency INR = Currency.getInstance("INR");

    private static Payment newPayment() {
        return Payment.create("PAY-1001", "idem-1", new BigDecimal("250.00"), INR,
                PaymentMethod.UPI, "payer-1", "payee-1", "Invoice 42");
    }

    @Nested
    class Create {

        @Test
        void startsPendingWithAnIdAndTheGivenDetails() {
            Payment payment = newPayment();

            assertThat(payment.getId()).isNotNull();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getReference()).isEqualTo("PAY-1001");
            assertThat(payment.getAmount()).isEqualByComparingTo("250");
            assertThat(payment.getCurrency()).isEqualTo(INR);
            assertThat(payment.getMethod()).isEqualTo(PaymentMethod.UPI);
            assertThat(payment.getVersion()).isNull(); // null tells Spring Data the entity is new
        }

        @Test
        void givesEveryPaymentADifferentId() {
            assertThat(newPayment().getId()).isNotEqualTo(newPayment().getId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
        void rejectsAmountsThatAreNotPositive(String amount) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Payment.create("PAY-1", null, new BigDecimal(amount), INR,
                            PaymentMethod.CARD, "payer-1", "payee-1", null))
                    .withMessageContaining("amount");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void rejectsBlankReference(String reference) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Payment.create(reference, null, BigDecimal.TEN, INR,
                            PaymentMethod.CARD, "payer-1", "payee-1", null))
                    .withMessageContaining("reference");
        }

        @Test
        void rejectsPayingYourself() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Payment.create("PAY-1", null, BigDecimal.TEN, INR,
                            PaymentMethod.CARD, "same", "same", null))
                    .withMessageContaining("different");
        }

        @Test
        void initialHistoryEntryRecordsCreationAsPending() {
            Payment payment = newPayment();

            PaymentStatusHistory entry = payment.initialHistoryEntry("api");

            assertThat(entry.getPaymentId()).isEqualTo(payment.getId());
            assertThat(entry.getFromStatus()).isNull();
            assertThat(entry.getToStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(entry.getChangedBy()).isEqualTo("api");
        }
    }

    @Nested
    class ChangeStatus {

        @Test
        void allowedChangeUpdatesStatusAndReturnsHistoryEntry() {
            Payment payment = newPayment();

            PaymentStatusHistory entry = payment.changeStatus(PaymentStatus.PROCESSING, "Sent to gateway", "ops");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(entry.getPaymentId()).isEqualTo(payment.getId());
            assertThat(entry.getFromStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(entry.getToStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(entry.getReason()).isEqualTo("Sent to gateway");
        }

        @Test
        void followsTheHappyPathToRefunded() {
            Payment payment = newPayment();

            payment.changeStatus(PaymentStatus.PROCESSING, null, "ops");
            payment.changeStatus(PaymentStatus.COMPLETED, null, "ops");
            payment.changeStatus(PaymentStatus.REFUNDED, "Customer request", "ops");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        void disallowedChangeThrowsAndLeavesStatusUnchanged() {
            Payment payment = newPayment();

            assertThatThrownBy(() -> payment.changeStatus(PaymentStatus.COMPLETED, null, "ops"))
                    .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                        assertThat(ex.getPaymentId()).isEqualTo(payment.getId());
                        assertThat(ex.getCurrentStatus()).isEqualTo(PaymentStatus.PENDING);
                        assertThat(ex.getRequestedStatus()).isEqualTo(PaymentStatus.COMPLETED);
                    });
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        void finalStatusCannotChange() {
            Payment payment = newPayment();
            payment.changeStatus(PaymentStatus.CANCELLED, null, "ops");

            assertThatThrownBy(() -> payment.changeStatus(PaymentStatus.PROCESSING, null, "ops"))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void rejectsNullTarget() {
            assertThatThrownBy(() -> newPayment().changeStatus(null, null, "ops"))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
