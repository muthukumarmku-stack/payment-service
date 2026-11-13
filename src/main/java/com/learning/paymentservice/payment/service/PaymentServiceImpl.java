package com.learning.paymentservice.payment.service;

import com.learning.paymentservice.common.web.PageResponse;
import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.exception.DuplicatePaymentReferenceException;
import com.learning.paymentservice.payment.exception.IdempotencyKeyReuseException;
import com.learning.paymentservice.payment.exception.PaymentNotFoundException;
import com.learning.paymentservice.payment.exception.UnsupportedSortPropertyException;
import com.learning.paymentservice.payment.mapper.PaymentMapper;
import com.learning.paymentservice.payment.repository.PaymentRepository;
import com.learning.paymentservice.payment.repository.PaymentSpecifications;
import com.learning.paymentservice.payment.repository.PaymentStatusHistoryRepository;
import com.learning.paymentservice.payment.web.dto.CreatePaymentRequest;
import com.learning.paymentservice.payment.web.dto.PaymentResponse;
import com.learning.paymentservice.payment.web.dto.PaymentSearchCriteria;
import com.learning.paymentservice.payment.web.dto.StatusHistoryResponse;
import com.learning.paymentservice.payment.web.dto.UpdatePaymentStatusRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinates the use cases: loads and saves through the repositories, lets the
 * domain enforce the business rules, and converts to DTOs with the mapper.
 *
 * <p>Reads run in read-only transactions; each write runs in one transaction, so
 * a payment and its history entry are always saved together or not at all.
 */
@Service
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    /** Who made a change. A placeholder until the API has authentication. */
    static final String API_ACTOR = "api";

    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    /**
     * Fields clients may sort the payment list by. Anything else is rejected with a
     * 400 instead of failing deep inside the query (or sorting by an internal column).
     */
    static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "updatedAt", "amount", "status", "reference");

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository historyRepository;
    private final PaymentMapper mapper;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                              PaymentStatusHistoryRepository historyRepository,
                              PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.historyRepository = historyRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request, String idempotencyKey) {
        Payment candidate = mapper.toNewPayment(request, idempotencyKey);

        Optional<Payment> replay = findIdempotentReplay(candidate);
        if (replay.isPresent()) {
            log.info("Idempotent replay of payment {} for key {}", replay.get().getId(), candidate.getIdempotencyKey());
            return mapper.toResponse(replay.get());
        }

        if (paymentRepository.existsByReference(candidate.getReference())) {
            throw new DuplicatePaymentReferenceException(candidate.getReference());
        }

        // saveAndFlush runs the INSERT now, so audit timestamps and the version are
        // set before mapping, and constraint violations surface inside this method.
        Payment saved = paymentRepository.saveAndFlush(candidate);
        historyRepository.save(saved.initialHistoryEntry(API_ACTOR));

        log.info("Created payment {} with reference {}", saved.getId(), saved.getReference());
        return mapper.toResponse(saved);
    }

    @Override
    public PaymentResponse getPayment(UUID paymentId) {
        return mapper.toResponse(loadPayment(paymentId));
    }

    @Override
    @Transactional
    public PaymentResponse updateStatus(UUID paymentId, UpdatePaymentStatusRequest request) {
        Payment payment = loadPayment(paymentId);
        var previousStatus = payment.getStatus();

        var historyEntry = payment.changeStatus(request.status(), request.reason(), API_ACTOR);

        // Flushing now bumps the version (failing on a concurrent update) and
        // refreshes updatedAt, so the response shows the stored values.
        Payment saved = paymentRepository.saveAndFlush(payment);
        historyRepository.save(historyEntry);

        log.info("Payment {} moved from {} to {}", paymentId, previousStatus, saved.getStatus());
        return mapper.toResponse(saved);
    }

    @Override
    public List<StatusHistoryResponse> getStatusHistory(UUID paymentId) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return historyRepository.findByPaymentIdOrderByChangedAtAscIdAsc(paymentId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public PageResponse<PaymentResponse> searchPayments(PaymentSearchCriteria criteria, Pageable pageable) {
        var specification = PaymentSpecifications.matching(mapper.toFilter(criteria));
        var page = paymentRepository.findAll(specification, withDefaultSort(pageable));
        return PageResponse.from(page, mapper::toResponse);
    }

    private Payment loadPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /**
     * When the request carries an idempotency key that was already used, the earlier
     * payment is the answer, but only if the request is the same one.
     */
    private Optional<Payment> findIdempotentReplay(Payment candidate) {
        String key = candidate.getIdempotencyKey();
        if (key == null) {
            return Optional.empty();
        }
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(key);
        if (existing.isPresent() && !sameRequest(existing.get(), candidate)) {
            throw new IdempotencyKeyReuseException(key);
        }
        return existing;
    }

    private static boolean sameRequest(Payment existing, Payment candidate) {
        return existing.getReference().equals(candidate.getReference())
                && existing.getAmount().compareTo(candidate.getAmount()) == 0
                && existing.getCurrency().equals(candidate.getCurrency())
                && existing.getMethod() == candidate.getMethod()
                && existing.getPayerId().equals(candidate.getPayerId())
                && existing.getPayeeId().equals(candidate.getPayeeId())
                && Objects.equals(existing.getDescription(), candidate.getDescription());
    }

    private static Pageable withDefaultSort(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return PageRequest.of(0, 20, DEFAULT_SORT);
        }
        if (pageable.getSort().isSorted()) {
            pageable.getSort().forEach(order -> {
                if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                    throw new UnsupportedSortPropertyException(order.getProperty(), SORTABLE_PROPERTIES);
                }
            });
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
    }
}
