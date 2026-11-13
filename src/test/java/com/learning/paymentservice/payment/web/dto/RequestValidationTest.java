package com.learning.paymentservice.payment.web.dto;

import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** The names of the fields that failed validation. */
    private static <T> Set<String> invalidFields(T object) {
        return validator.validate(object).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Nested
    class CreatePayment {

        private static CreatePaymentRequest valid() {
            return new CreatePaymentRequest("PAY-1", new BigDecimal("99.99"), "INR",
                    PaymentMethod.UPI, "payer-1", "payee-1", "Invoice 7");
        }

        private static CreatePaymentRequest withAmount(String amount) {
            var v = valid();
            return new CreatePaymentRequest(v.reference(), amount == null ? null : new BigDecimal(amount),
                    v.currency(), v.method(), v.payerId(), v.payeeId(), v.description());
        }

        private static CreatePaymentRequest withCurrency(String currency) {
            var v = valid();
            return new CreatePaymentRequest(v.reference(), v.amount(), currency,
                    v.method(), v.payerId(), v.payeeId(), v.description());
        }

        @Test
        void validRequestHasNoViolations() {
            assertThat(invalidFields(valid())).isEmpty();
        }

        @Test
        void descriptionIsOptional() {
            var v = valid();
            var request = new CreatePaymentRequest(v.reference(), v.amount(), v.currency(),
                    v.method(), v.payerId(), v.payeeId(), null);

            assertThat(invalidFields(request)).isEmpty();
        }

        @Test
        void reportsEveryMissingRequiredField() {
            var empty = new CreatePaymentRequest(null, null, null, null, null, null, null);

            assertThat(invalidFields(empty))
                    .containsExactlyInAnyOrder("reference", "amount", "currency", "method", "payerId", "payeeId");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-5", "0.00001", "1234567890123456"})
        void rejectsBadAmounts(String amount) {
            // zero, negative, more than 4 decimals, more than 15 integer digits
            assertThat(invalidFields(withAmount(amount))).containsExactly("amount");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.0001", "1", "999999999999999.9999"})
        void acceptsAmountsThatFitTheColumn(String amount) {
            assertThat(invalidFields(withAmount(amount))).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"inr", "IN", "INRR", "XYZ", "12A"})
        void rejectsInvalidCurrencies(String currency) {
            assertThat(invalidFields(withCurrency(currency))).containsExactly("currency");
        }

        @Test
        void rejectsPayingYourselfEvenWithSurroundingSpaces() {
            var v = valid();
            var request = new CreatePaymentRequest(v.reference(), v.amount(), v.currency(), v.method(),
                    "payer-1", " payer-1 ", v.description());

            assertThat(invalidFields(request)).containsExactly("payeeDifferentFromPayer");
        }

        @Test
        void rejectsFieldsLongerThanTheirColumns() {
            var v = valid();
            var request = new CreatePaymentRequest("R".repeat(65), v.amount(), v.currency(), v.method(),
                    "P".repeat(65), "Q".repeat(65), "D".repeat(256));

            assertThat(invalidFields(request))
                    .containsExactlyInAnyOrder("reference", "payerId", "payeeId", "description");
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        void statusIsRequiredAndReasonIsOptional() {
            assertThat(invalidFields(new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, null))).isEmpty();
            assertThat(invalidFields(new UpdatePaymentStatusRequest(null, "why"))).containsExactly("status");
        }

        @Test
        void rejectsOverlongReason() {
            assertThat(invalidFields(new UpdatePaymentStatusRequest(PaymentStatus.FAILED, "x".repeat(256))))
                    .containsExactly("reason");
        }
    }

    @Nested
    class SearchCriteria {

        private final Instant earlier = Instant.parse("2026-11-01T00:00:00Z");
        private final Instant later = Instant.parse("2026-11-30T00:00:00Z");

        @Test
        void everythingIsOptional() {
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, null, null))).isEmpty();
        }

        @Test
        void acceptsAnOpenEndedOrOrderedRange() {
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, earlier, null))).isEmpty();
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, null, later))).isEmpty();
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, earlier, later))).isEmpty();
        }

        @Test
        void rejectsReversedOrEmptyRange() {
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, later, earlier)))
                    .containsExactly("dateRangeValid");
            assertThat(invalidFields(new PaymentSearchCriteria(null, null, earlier, earlier)))
                    .containsExactly("dateRangeValid");
        }
    }
}
