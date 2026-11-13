package com.learning.paymentservice.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.learning.paymentservice.payment.domain.PaymentStatus.CANCELLED;
import static com.learning.paymentservice.payment.domain.PaymentStatus.COMPLETED;
import static com.learning.paymentservice.payment.domain.PaymentStatus.FAILED;
import static com.learning.paymentservice.payment.domain.PaymentStatus.PENDING;
import static com.learning.paymentservice.payment.domain.PaymentStatus.PROCESSING;
import static com.learning.paymentservice.payment.domain.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    /** The lifecycle written out once, independently of the implementation. */
    private static final Set<String> ALLOWED = Set.of(
            "PENDING->PROCESSING",
            "PENDING->CANCELLED",
            "PROCESSING->COMPLETED",
            "PROCESSING->FAILED",
            "COMPLETED->REFUNDED");

    static Stream<Arguments> everyPairOfStatuses() {
        return Arrays.stream(PaymentStatus.values())
                .flatMap(from -> Arrays.stream(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPairOfStatuses")
    @DisplayName("allows exactly the transitions in the lifecycle")
    void allowsExactlyTheDocumentedTransitions(PaymentStatus from, PaymentStatus to) {
        boolean expected = ALLOWED.contains(from + "->" + to);

        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void neverAllowsStayingInTheSameStatus(PaymentStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void rejectsNullTarget(PaymentStatus status) {
        assertThat(status.canTransitionTo(null)).isFalse();
    }

    @Test
    void finalStatusesAreFailedCancelledAndRefunded() {
        assertThat(Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isFinal))
                .containsExactlyInAnyOrder(FAILED, CANCELLED, REFUNDED);
        assertThat(Stream.of(PENDING, PROCESSING, COMPLETED)).noneMatch(PaymentStatus::isFinal);
    }

    @Test
    void allowedNextStatusesCannotBeModifiedByCallers() {
        PENDING.allowedNextStatuses().clear();

        assertThat(PENDING.canTransitionTo(PROCESSING)).isTrue();
    }
}
