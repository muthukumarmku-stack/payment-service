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
import com.learning.paymentservice.payment.mapper.PaymentMapper;
import com.learning.paymentservice.payment.repository.PaymentRepository;
import com.learning.paymentservice.payment.repository.PaymentStatusHistoryRepository;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentServiceImpl} in the classic Mockito style
 * ({@code @InjectMocks}, {@code when}, {@code verify}, {@code assertThrows}).
 *
 * <p>No Spring context is started: {@link MockitoExtension} creates the mocks and the
 * service object directly, so the whole class runs in milliseconds. The repositories
 * are mocks (no database); the mapper is a {@link Spy} on the real {@link PaymentMapper},
 * so the conversions are real but its calls can still be verified.
 *
 * <p>Every test follows Arrange / Act / Assert, marked with comments.
 * {@link PaymentServiceImplTest} covers the same class in BDD style ({@code given}/{@code then}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentStatusHistoryRepository historyRepository;

    @Spy
    private PaymentMapper mapper = new PaymentMapper();

    @InjectMocks
    private PaymentServiceImpl paymentService;

    // ---- Test data -------------------------------------------------------------

    private static CreatePaymentRequest createRequest() {
        return new CreatePaymentRequest("PAY-2001", new BigDecimal("750.25"), "INR",
                PaymentMethod.CARD, "payer-1", "payee-1", "Laptop order");
    }

    private static Payment pendingPayment(String reference) {
        return Payment.create(reference, null, new BigDecimal("100.00"),
                java.util.Currency.getInstance("INR"), PaymentMethod.UPI, "payer-1", "payee-1", null);
    }

    /** A payment that has already been moved to {@code status} along a legal path. */
    private static Payment paymentIn(PaymentStatus status) {
        Payment payment = pendingPayment("PAY-" + status);
        switch (status) {
            case PENDING -> { }
            case PROCESSING -> payment.changeStatus(PaymentStatus.PROCESSING, null, "test");
            case COMPLETED -> {
                payment.changeStatus(PaymentStatus.PROCESSING, null, "test");
                payment.changeStatus(PaymentStatus.COMPLETED, null, "test");
            }
            case FAILED -> {
                payment.changeStatus(PaymentStatus.PROCESSING, null, "test");
                payment.changeStatus(PaymentStatus.FAILED, null, "test");
            }
            case CANCELLED -> payment.changeStatus(PaymentStatus.CANCELLED, null, "test");
            case REFUNDED -> {
                payment.changeStatus(PaymentStatus.PROCESSING, null, "test");
                payment.changeStatus(PaymentStatus.COMPLETED, null, "test");
                payment.changeStatus(PaymentStatus.REFUNDED, null, "test");
            }
        }
        return payment;
    }

    /** Makes saveAndFlush return the entity it receives, as the real repository does. */
    private void saveAndFlushReturnsItsArgument() {
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ---- 1. Create -------------------------------------------------------------

    @Nested
    @DisplayName("createPayment")
    class CreatePayment {

        @Test
        @DisplayName("saves a PENDING payment, then its first history entry")
        void createPayment_validRequest_savesPendingPaymentThenInitialHistoryEntry() {
            // Arrange
            when(paymentRepository.existsByReference("PAY-2001")).thenReturn(false);
            saveAndFlushReturnsItsArgument();
            ArgumentCaptor<PaymentStatusHistory> historyCaptor = ArgumentCaptor.forClass(PaymentStatusHistory.class);

            // Act
            PaymentResponse response = paymentService.createPayment(createRequest(), null);

            // Assert: the response
            assertAll(
                    () -> assertNotNull(response.id()),
                    () -> assertEquals("PAY-2001", response.reference()),
                    () -> assertEquals(0, new BigDecimal("750.25").compareTo(response.amount())),
                    () -> assertEquals("INR", response.currency()),
                    () -> assertEquals(PaymentMethod.CARD, response.method()),
                    () -> assertEquals(PaymentStatus.PENDING, response.status()));

            // Assert: the repository calls, in order, and nothing else
            InOrder order = inOrder(paymentRepository, historyRepository);
            order.verify(paymentRepository).existsByReference("PAY-2001");
            order.verify(paymentRepository).saveAndFlush(any(Payment.class));
            order.verify(historyRepository).save(historyCaptor.capture());
            verifyNoMoreInteractions(paymentRepository, historyRepository);

            // Assert: the history entry records the creation
            PaymentStatusHistory entry = historyCaptor.getValue();
            assertAll(
                    () -> assertEquals(response.id(), entry.getPaymentId()),
                    () -> assertNull(entry.getFromStatus()),
                    () -> assertEquals(PaymentStatus.PENDING, entry.getToStatus()),
                    () -> assertEquals(PaymentServiceImpl.API_ACTOR, entry.getChangedBy()));
        }

        @Test
        @DisplayName("without an Idempotency-Key, never looks one up")
        void createPayment_withoutIdempotencyKey_skipsIdempotencyLookup() {
            // Arrange
            when(paymentRepository.existsByReference(anyString())).thenReturn(false);
            saveAndFlushReturnsItsArgument();

            // Act
            paymentService.createPayment(createRequest(), null);

            // Assert
            verify(paymentRepository, never()).findByIdempotencyKey(anyString());
        }

        @Test
        @DisplayName("with a new Idempotency-Key, stores the key on the payment")
        void createPayment_withUnusedIdempotencyKey_storesKeyOnNewPayment() {
            // Arrange
            when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
            when(paymentRepository.existsByReference("PAY-2001")).thenReturn(false);
            saveAndFlushReturnsItsArgument();
            ArgumentCaptor<Payment> savedCaptor = ArgumentCaptor.forClass(Payment.class);

            // Act
            paymentService.createPayment(createRequest(), "key-1");

            // Assert
            verify(paymentRepository).findByIdempotencyKey("key-1");
            verify(paymentRepository).saveAndFlush(savedCaptor.capture());
            assertEquals("key-1", savedCaptor.getValue().getIdempotencyKey());
        }

        @Test
        @DisplayName("retry with the same key and body returns the original payment and saves nothing")
        void createPayment_identicalRetryWithSameKey_returnsOriginalWithoutSaving() {
            // Arrange
            Payment original = mapper.toNewPayment(createRequest(), "key-1");
            when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

            // Act
            PaymentResponse response = paymentService.createPayment(createRequest(), "key-1");

            // Assert
            assertEquals(original.getId(), response.id());
            verify(paymentRepository, never()).existsByReference(anyString());
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("same key with a different description is rejected")
        void createPayment_sameKeyWithDifferentDescription_throwsIdempotencyKeyReuseException() {
            // Arrange
            Payment original = mapper.toNewPayment(createRequest(), "key-1");
            when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));
            CreatePaymentRequest r = createRequest();
            CreatePaymentRequest changed = new CreatePaymentRequest(r.reference(), r.amount(), r.currency(),
                    r.method(), r.payerId(), r.payeeId(), "A different description");

            // Act
            IdempotencyKeyReuseException thrown = assertThrows(IdempotencyKeyReuseException.class,
                    () -> paymentService.createPayment(changed, "key-1"));

            // Assert
            assertEquals("key-1", thrown.getIdempotencyKey());
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        /** Each request differs from {@link #createRequest()} in exactly one field. */
        static Stream<Arguments> requestsDifferingInOneField() {
            CreatePaymentRequest r = createRequest();
            return Stream.of(
                    Arguments.of("reference", new CreatePaymentRequest("PAY-OTHER", r.amount(), r.currency(),
                            r.method(), r.payerId(), r.payeeId(), r.description())),
                    Arguments.of("currency", new CreatePaymentRequest(r.reference(), r.amount(), "USD",
                            r.method(), r.payerId(), r.payeeId(), r.description())),
                    Arguments.of("method", new CreatePaymentRequest(r.reference(), r.amount(), r.currency(),
                            PaymentMethod.WALLET, r.payerId(), r.payeeId(), r.description())),
                    Arguments.of("payerId", new CreatePaymentRequest(r.reference(), r.amount(), r.currency(),
                            r.method(), "payer-2", r.payeeId(), r.description())),
                    Arguments.of("payeeId", new CreatePaymentRequest(r.reference(), r.amount(), r.currency(),
                            r.method(), r.payerId(), "payee-2", r.description())));
        }

        @ParameterizedTest(name = "different {0}")
        @MethodSource("requestsDifferingInOneField")
        @DisplayName("same key with any other field changed is rejected")
        void createPayment_sameKeyWithOneFieldChanged_throwsIdempotencyKeyReuseException(
                String changedField, CreatePaymentRequest changedRequest) {
            // Arrange
            Payment original = mapper.toNewPayment(createRequest(), "key-1");
            when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

            // Act + Assert
            assertThrows(IdempotencyKeyReuseException.class,
                    () -> paymentService.createPayment(changedRequest, "key-1"),
                    "a retry with a different " + changedField + " must not reuse the original payment");
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("duplicate reference is rejected before anything is saved")
        void createPayment_duplicateReference_throwsDuplicatePaymentReferenceException() {
            // Arrange
            when(paymentRepository.existsByReference("PAY-2001")).thenReturn(true);

            // Act
            DuplicatePaymentReferenceException thrown = assertThrows(DuplicatePaymentReferenceException.class,
                    () -> paymentService.createPayment(createRequest(), null));

            // Assert
            assertEquals("PAY-2001", thrown.getReference());
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("if the database rejects the insert, the error propagates and no history is written")
        void createPayment_databaseRejectsInsert_propagatesExceptionAndSkipsHistory() {
            // Arrange: both checks pass, but a simultaneous request wins the unique key
            when(paymentRepository.existsByReference("PAY-2001")).thenReturn(false);
            when(paymentRepository.saveAndFlush(any(Payment.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_payments_reference"));

            // Act + Assert
            assertThrows(DataIntegrityViolationException.class,
                    () -> paymentService.createPayment(createRequest(), null));
            verifyNoInteractions(historyRepository);
        }
    }

    // ---- 2. Get by id ----------------------------------------------------------

    @Nested
    @DisplayName("getPayment")
    class GetPayment {

        @Test
        @DisplayName("returns the payment mapped to a response")
        void getPaymentById_existingId_returnsMappedPayment() {
            // Arrange
            Payment payment = pendingPayment("PAY-3001");
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            // Act
            PaymentResponse response = paymentService.getPayment(payment.getId());

            // Assert
            assertAll(
                    () -> assertEquals(payment.getId(), response.id()),
                    () -> assertEquals("PAY-3001", response.reference()),
                    () -> assertEquals(PaymentStatus.PENDING, response.status()));
            verify(paymentRepository).findById(payment.getId());
            verify(mapper).toResponse(payment);
            verifyNoMoreInteractions(paymentRepository);
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("unknown id throws PaymentNotFoundException")
        void getPaymentById_unknownId_throwsPaymentNotFoundException() {
            // Arrange
            UUID unknownId = UUID.randomUUID();
            when(paymentRepository.findById(unknownId)).thenReturn(Optional.empty());

            // Act
            PaymentNotFoundException thrown = assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.getPayment(unknownId));

            // Assert
            assertEquals(unknownId, thrown.getPaymentId());
            assertEquals("Payment " + unknownId + " was not found", thrown.getMessage());
            verify(mapper, never()).toResponse(any(Payment.class));
        }
    }

    // ---- 3. Retrieve all (paged list) ------------------------------------------

    @Nested
    @DisplayName("searchPayments (retrieve all)")
    class RetrieveAllPayments {

        @SuppressWarnings("unchecked")
        private void repositoryReturnsPageOf(List<Payment> payments) {
            when(paymentRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(payments, call.getArgument(1), payments.size()));
        }

        @Test
        @DisplayName("with no filters, returns every payment, newest first")
        @SuppressWarnings("unchecked")
        void searchPayments_noFilters_returnsAllPaymentsNewestFirst() {
            // Arrange
            List<Payment> payments = List.of(pendingPayment("PAY-A"), pendingPayment("PAY-B"), pendingPayment("PAY-C"));
            repositoryReturnsPageOf(payments);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

            // Act
            PageResponse<PaymentResponse> page = paymentService.searchPayments(
                    new PaymentSearchCriteria(null, null, null, null), PageRequest.of(0, 20));

            // Assert: everything came back, mapped and counted
            assertEquals(3, page.totalElements());
            assertEquals(1, page.totalPages());
            assertEquals(List.of("PAY-A", "PAY-B", "PAY-C"),
                    page.content().stream().map(PaymentResponse::reference).toList());

            // Assert: the repository was asked once, with the default newest-first sort
            verify(paymentRepository).findAll(any(Specification.class), pageableCaptor.capture());
            assertEquals(PaymentServiceImpl.DEFAULT_SORT, pageableCaptor.getValue().getSort());
            verifyNoMoreInteractions(paymentRepository);
        }

        @Test
        @DisplayName("null criteria and an unpaged request fall back to the first 20, newest first")
        @SuppressWarnings("unchecked")
        void searchPayments_nullCriteriaAndUnpaged_usesFirstPageOfTwentyNewestFirst() {
            // Arrange
            repositoryReturnsPageOf(List.of());
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

            // Act
            paymentService.searchPayments(null, Pageable.unpaged());

            // Assert
            verify(paymentRepository).findAll(any(Specification.class), pageableCaptor.capture());
            Pageable used = pageableCaptor.getValue();
            assertAll(
                    () -> assertEquals(0, used.getPageNumber()),
                    () -> assertEquals(20, used.getPageSize()),
                    () -> assertEquals(PaymentServiceImpl.DEFAULT_SORT, used.getSort()));
        }

        @Test
        @DisplayName("no matches gives an empty page, not an error")
        void searchPayments_noMatchingPayments_returnsEmptyPage() {
            // Arrange
            repositoryReturnsPageOf(List.of());

            // Act
            PageResponse<PaymentResponse> page = paymentService.searchPayments(
                    new PaymentSearchCriteria(PaymentStatus.REFUNDED, "nobody", null, null), PageRequest.of(0, 20));

            // Assert
            assertTrue(page.content().isEmpty());
            assertEquals(0, page.totalElements());
        }

        @Test
        @DisplayName("keeps a caller sort that uses only allowed fields")
        @SuppressWarnings("unchecked")
        void searchPayments_sortOnAllowedFields_keepsCallerSort() {
            // Arrange
            repositoryReturnsPageOf(List.of());
            Pageable requested = PageRequest.of(1, 5, Sort.by(Sort.Order.desc("amount"), Sort.Order.asc("reference")));
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

            // Act
            paymentService.searchPayments(null, requested);

            // Assert
            verify(paymentRepository).findAll(any(Specification.class), pageableCaptor.capture());
            assertSame(requested, pageableCaptor.getValue());
        }
    }

    // ---- 4. Update status ------------------------------------------------------

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("allowed change saves the payment, then the history entry")
        void updateStatus_allowedTransition_savesPaymentThenHistoryEntry() {
            // Arrange
            Payment payment = pendingPayment("PAY-4001");
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
            saveAndFlushReturnsItsArgument();
            ArgumentCaptor<PaymentStatusHistory> historyCaptor = ArgumentCaptor.forClass(PaymentStatusHistory.class);

            // Act
            PaymentResponse response = paymentService.updateStatus(payment.getId(),
                    new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, "Sent to gateway"));

            // Assert: response and entity both show the new status
            assertEquals(PaymentStatus.PROCESSING, response.status());
            assertEquals(PaymentStatus.PROCESSING, payment.getStatus());

            // Assert: load, save, then history, in that order
            InOrder order = inOrder(paymentRepository, historyRepository);
            order.verify(paymentRepository).findById(payment.getId());
            order.verify(paymentRepository).saveAndFlush(payment);
            order.verify(historyRepository).save(historyCaptor.capture());
            verifyNoMoreInteractions(paymentRepository, historyRepository);

            PaymentStatusHistory entry = historyCaptor.getValue();
            assertAll(
                    () -> assertEquals(PaymentStatus.PENDING, entry.getFromStatus()),
                    () -> assertEquals(PaymentStatus.PROCESSING, entry.getToStatus()),
                    () -> assertEquals("Sent to gateway", entry.getReason()),
                    () -> assertEquals(PaymentServiceImpl.API_ACTOR, entry.getChangedBy()));
        }

        @Test
        @DisplayName("skipping a step (PENDING to COMPLETED) is rejected and nothing is saved")
        void updateStatus_invalidTransition_throwsAndSavesNothing() {
            // Arrange
            Payment payment = pendingPayment("PAY-4002");
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
            UpdatePaymentStatusRequest request = new UpdatePaymentStatusRequest(PaymentStatus.COMPLETED, null);

            // Act
            InvalidStatusTransitionException thrown = assertThrows(InvalidStatusTransitionException.class,
                    () -> paymentService.updateStatus(payment.getId(), request));

            // Assert
            assertAll(
                    () -> assertEquals(PaymentStatus.PENDING, thrown.getCurrentStatus()),
                    () -> assertEquals(PaymentStatus.COMPLETED, thrown.getRequestedStatus()),
                    () -> assertEquals(PaymentStatus.PENDING, payment.getStatus()));
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED", "REFUNDED"})
        @DisplayName("a payment in a final status can never change again")
        void updateStatus_fromFinalStatus_throwsInvalidStatusTransitionException(PaymentStatus finalStatus) {
            // Arrange
            Payment payment = paymentIn(finalStatus);
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            // Act + Assert
            assertThrows(InvalidStatusTransitionException.class, () -> paymentService.updateStatus(
                    payment.getId(), new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, null)));
            assertEquals(finalStatus, payment.getStatus());
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("unknown id throws PaymentNotFoundException")
        void updateStatus_unknownId_throwsPaymentNotFoundException() {
            // Arrange
            UUID unknownId = UUID.randomUUID();
            when(paymentRepository.findById(unknownId)).thenReturn(Optional.empty());

            // Act
            PaymentNotFoundException thrown = assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.updateStatus(unknownId,
                            new UpdatePaymentStatusRequest(PaymentStatus.PROCESSING, null)));

            // Assert
            assertEquals(unknownId, thrown.getPaymentId());
            verify(paymentRepository, never()).saveAndFlush(any());
            verifyNoInteractions(historyRepository);
        }

        @Test
        @DisplayName("a concurrent update makes the save fail and no history is written")
        void updateStatus_concurrentModification_propagatesExceptionAndSkipsHistory() {
            // Arrange: another request bumped the version between our read and our write
            Payment payment = pendingPayment("PAY-4003");
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
            when(paymentRepository.saveAndFlush(payment))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, payment.getId()));

            // Act + Assert
            assertThrows(ObjectOptimisticLockingFailureException.class, () -> paymentService.updateStatus(
                    payment.getId(), new UpdatePaymentStatusRequest(PaymentStatus.CANCELLED, null)));
            verifyNoInteractions(historyRepository);
        }
    }

    // ---- 5. Status history -----------------------------------------------------

    @Nested
    @DisplayName("getStatusHistory")
    class StatusHistory {

        @Test
        @DisplayName("an existing payment with no entries gives an empty list")
        void getStatusHistory_existingPaymentWithoutEntries_returnsEmptyList() {
            // Arrange
            UUID id = UUID.randomUUID();
            when(paymentRepository.existsById(id)).thenReturn(true);
            when(historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(id)).thenReturn(List.of());

            // Act
            List<StatusHistoryResponse> history = paymentService.getStatusHistory(id);

            // Assert
            assertTrue(history.isEmpty());
            verify(paymentRepository).existsById(id);
            verify(historyRepository).findByPaymentIdOrderByChangedAtAscIdAsc(id);
        }
    }

    /** Guards the test setup itself: @InjectMocks must have wired the mocks, not nulls. */
    @Test
    @DisplayName("@InjectMocks builds the service with the mocks")
    void injectMocks_buildsServiceWithMocks() {
        // Arrange + Act
        Page<Payment> empty = Page.empty();
        when(paymentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        // Assert
        assertNotNull(paymentService);
        assertEquals(0, paymentService.searchPayments(null, PageRequest.of(0, 1)).totalElements());
    }
}
