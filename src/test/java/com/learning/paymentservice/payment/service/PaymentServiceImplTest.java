package com.learning.paymentservice.payment.service;

import com.learning.paymentservice.common.web.PageResponse;
import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import com.learning.paymentservice.payment.domain.PaymentStatusHistory;
import com.learning.paymentservice.payment.exception.DuplicatePaymentReferenceException;
import com.learning.paymentservice.payment.exception.IdempotencyKeyReuseException;
import com.learning.paymentservice.payment.exception.InvalidStatusTransitionException;
import com.learning.paymentservice.payment.exception.PaymentNotFoundException;
import com.learning.paymentservice.payment.exception.UnsupportedSortPropertyException;
import com.learning.paymentservice.payment.mapper.PaymentMapper;
import com.learning.paymentservice.payment.repository.PaymentRepository;
import com.learning.paymentservice.payment.repository.PaymentStatusHistoryRepository;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentStatusHistoryRepository historyRepository;

    @Captor
    private ArgumentCaptor<PaymentStatusHistory> historyCaptor;

    private final PaymentMapper mapper = new PaymentMapper();

    private PaymentServiceImpl service;

    @BeforeEach
    void createService() {
        service = new PaymentServiceImpl(paymentRepository, historyRepository, mapper);
    }

    private static CreatePaymentRequest createRequest() {
        return new CreatePaymentRequest("PAY-100", new BigDecimal("500.00"), "INR",
                PaymentMethod.UPI, "payer-1", "payee-1", "Order 100");
    }

    private Payment existingPayment() {
        return mapper.toNewPayment(createRequest(), null);
    }

    /** Makes saveAndFlush hand back the entity it was given, like the real repository. */
    private void saveReturnsItsArgument() {
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(call -> call.getArgument(0));
    }

    @Nested
    class CreatePayment {

        @Test
        void savesAPendingPaymentAndItsFirstHistoryEntry() {
            given(paymentRepository.existsByReference("PAY-100")).willReturn(false);
            saveReturnsItsArgument();

            PaymentResponse response = service.createPayment(createRequest(), null);

            assertThat(response.id()).isNotNull();
            assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(response.reference()).isEqualTo("PAY-100");

            then(historyRepository).should().save(historyCaptor.capture());
            PaymentStatusHistory entry = historyCaptor.getValue();
            assertThat(entry.getPaymentId()).isEqualTo(response.id());
            assertThat(entry.getFromStatus()).isNull();
            assertThat(entry.getToStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(entry.getChangedBy()).isEqualTo(PaymentServiceImpl.API_ACTOR);
        }

        @Test
        void withoutAnIdempotencyKeyNeverLooksOneUp() {
            given(paymentRepository.existsByReference("PAY-100")).willReturn(false);
            saveReturnsItsArgument();

            service.createPayment(createRequest(), null);

            then(paymentRepository).should(never()).findByIdempotencyKey(anyString());
        }

        @Test
        void rejectsADuplicateReferenceWithoutSaving() {
            given(paymentRepository.existsByReference("PAY-100")).willReturn(true);

            assertThatThrownBy(() -> service.createPayment(createRequest(), null))
                    .isInstanceOf(DuplicatePaymentReferenceException.class)
                    .hasMessageContaining("PAY-100");

            then(paymentRepository).should(never()).saveAndFlush(any());
            then(historyRepository).shouldHaveNoInteractions();
        }

        @Test
        void storesTheIdempotencyKeyOnTheNewPayment() {
            given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
            given(paymentRepository.existsByReference("PAY-100")).willReturn(false);
            saveReturnsItsArgument();

            service.createPayment(createRequest(), "key-1");

            ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
            then(paymentRepository).should().saveAndFlush(saved.capture());
            assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-1");
        }

        @Test
        void identicalRetryWithTheSameKeyReturnsTheOriginalPayment() {
            Payment original = mapper.toNewPayment(createRequest(), "key-1");
            given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(original));

            PaymentResponse response = service.createPayment(createRequest(), "key-1");

            assertThat(response.id()).isEqualTo(original.getId());
            then(paymentRepository).should(never()).saveAndFlush(any());
            then(historyRepository).shouldHaveNoInteractions();
        }

        @Test
        void sameKeyWithADifferentBodyIsRejected() {
            Payment original = mapper.toNewPayment(createRequest(), "key-1");
            given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(original));
            var r = createRequest();
            var differentAmount = new CreatePaymentRequest(r.reference(), new BigDecimal("999.00"), r.currency(),
                    r.method(), r.payerId(), r.payeeId(), r.description());

            assertThatThrownBy(() -> service.createPayment(differentAmount, "key-1"))
                    .isInstanceOf(IdempotencyKeyReuseException.class);

            then(paymentRepository).should(never()).saveAndFlush(any());
        }

        @Test
        void amountsThatDifferOnlyInScaleCountAsTheSameRequest() {
            Payment original = mapper.toNewPayment(createRequest(), "key-1"); // 500.00
            given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(original));
            var r = createRequest();
            var sameAmount = new CreatePaymentRequest(r.reference(), new BigDecimal("500"), r.currency(),
                    r.method(), r.payerId(), r.payeeId(), r.description());

            assertThat(service.createPayment(sameAmount, "key-1").id()).isEqualTo(original.getId());
        }
    }

    @Nested
    class GetPayment {

        @Test
        void returnsThePayment() {
            Payment payment = existingPayment();
            given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));

            assertThat(service.getPayment(payment.getId()).id()).isEqualTo(payment.getId());
        }

        @Test
        void unknownIdThrowsNotFound() {
            UUID id = UUID.randomUUID();
            given(paymentRepository.findById(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPayment(id))
                    .isInstanceOf(PaymentNotFoundException.class)
                    .hasMessageContaining(id.toString());
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        void allowedChangeIsSavedWithAHistoryEntry() {
            Payment payment = existingPayment();
            given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));
            saveReturnsItsArgument();

            PaymentResponse response = service.updateStatus(payment.getId(),
                    new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, "Sent to gateway"));

            assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
            then(paymentRepository).should().saveAndFlush(payment);
            then(historyRepository).should().save(historyCaptor.capture());
            assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(historyCaptor.getValue().getReason()).isEqualTo("Sent to gateway");
        }

        @Test
        void disallowedChangeSavesNothing() {
            Payment payment = existingPayment();
            given(paymentRepository.findById(payment.getId())).willReturn(Optional.of(payment));

            assertThatThrownBy(() -> service.updateStatus(payment.getId(),
                    new UpdatePaymentStatusRequest(PaymentStatus.REFUNDED, null)))
                    .isInstanceOf(InvalidStatusTransitionException.class);

            then(paymentRepository).should(never()).saveAndFlush(any());
            then(historyRepository).shouldHaveNoInteractions();
        }

        @Test
        void unknownIdThrowsNotFound() {
            UUID id = UUID.randomUUID();
            given(paymentRepository.findById(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStatus(id,
                    new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, null)))
                    .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    class StatusHistory {

        @Test
        void returnsTheEntriesInRepositoryOrder() {
            Payment payment = existingPayment();
            var created = payment.initialHistoryEntry("api");
            var processing = payment.changeStatus(PaymentStatus.PROCESSING, null, "api");
            given(paymentRepository.existsById(payment.getId())).willReturn(true);
            given(historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(payment.getId()))
                    .willReturn(List.of(created, processing));

            List<StatusHistoryResponse> history = service.getStatusHistory(payment.getId());

            assertThat(history).extracting(StatusHistoryResponse::toStatus)
                    .containsExactly(PaymentStatus.PENDING, PaymentStatus.PROCESSING);
        }

        @Test
        void unknownPaymentThrowsNotFoundInsteadOfReturningAnEmptyList() {
            UUID id = UUID.randomUUID();
            given(paymentRepository.existsById(id)).willReturn(false);

            assertThatThrownBy(() -> service.getStatusHistory(id)).isInstanceOf(PaymentNotFoundException.class);
            then(historyRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    class SearchPayments {

        @Captor
        private ArgumentCaptor<Pageable> pageableCaptor;

        @SuppressWarnings("unchecked")
        private void repositoryReturns(List<Payment> payments) {
            given(paymentRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .willAnswer(call -> new PageImpl<>(payments, call.getArgument(1), payments.size()));
        }

        @Test
        @SuppressWarnings("unchecked")
        void sortsNewestFirstWhenTheCallerGivesNoSort() {
            repositoryReturns(List.of());

            service.searchPayments(new PaymentSearchCriteria(null, null, null, null), PageRequest.of(2, 10));

            then(paymentRepository).should().findAll(any(Specification.class), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
            assertThat(pageableCaptor.getValue().getSort()).isEqualTo(PaymentServiceImpl.DEFAULT_SORT);
        }

        @Test
        @SuppressWarnings("unchecked")
        void keepsTheCallersSort() {
            repositoryReturns(List.of());
            var bySmallestAmount = PageRequest.of(0, 5, Sort.by("amount"));

            service.searchPayments(null, bySmallestAmount);

            then(paymentRepository).should().findAll(any(Specification.class), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue()).isEqualTo(bySmallestAmount);
        }

        @Test
        void rejectsSortingByAFieldOutsideTheAllowList() {
            var bySecretColumn = PageRequest.of(0, 5, Sort.by("createdAt").and(Sort.by("idempotencyKey")));

            assertThatThrownBy(() -> service.searchPayments(null, bySecretColumn))
                    .isInstanceOf(UnsupportedSortPropertyException.class)
                    .hasMessageContaining("idempotencyKey");
            then(paymentRepository).shouldHaveNoInteractions();
        }

        @Test
        void mapsTheResultsIntoAPageResponse() {
            Payment payment = existingPayment();
            repositoryReturns(List.of(payment));

            PageResponse<PaymentResponse> page = service.searchPayments(
                    new PaymentSearchCriteria(PaymentStatus.PENDING, "payer-1", null, null), PageRequest.of(0, 20));

            assertThat(page.totalElements()).isEqualTo(1);
            assertThat(page.content()).extracting(PaymentResponse::id).containsExactly(payment.getId());
        }
    }
}
