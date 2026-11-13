package com.learning.paymentservice.payment.repository;

import com.learning.paymentservice.TestcontainersConfiguration;
import com.learning.paymentservice.common.config.JpaAuditingConfig;
import com.learning.paymentservice.payment.domain.Payment;
import com.learning.paymentservice.payment.domain.PaymentMethod;
import com.learning.paymentservice.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static Payment payment(String reference, String payerId) {
        return Payment.create(reference, null, new BigDecimal("1234.5678"), Currency.getInstance("INR"),
                PaymentMethod.CARD, payerId, "merchant-1", "Order " + reference);
    }

    private Payment saveAndReload(Payment payment) {
        paymentRepository.saveAndFlush(payment);
        entityManager.clear(); // force the next read to come from the database
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    @Nested
    class Persistence {

        @Test
        void savesAndLoadsEveryField() {
            Payment original = Payment.create("PAY-1", "idem-1", new BigDecimal("1234.5678"),
                    Currency.getInstance("INR"), PaymentMethod.UPI, "payer-1", "payee-1", "Rent");

            Payment loaded = saveAndReload(original);

            assertThat(loaded).isEqualTo(original);
            assertThat(loaded.getReference()).isEqualTo("PAY-1");
            assertThat(loaded.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(loaded.getAmount()).isEqualByComparingTo("1234.5678");
            assertThat(loaded.getCurrency()).isEqualTo(Currency.getInstance("INR"));
            assertThat(loaded.getMethod()).isEqualTo(PaymentMethod.UPI);
            assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(loaded.getPayerId()).isEqualTo("payer-1");
            assertThat(loaded.getPayeeId()).isEqualTo("payee-1");
            assertThat(loaded.getDescription()).isEqualTo("Rent");
        }

        @Test
        void fillsAuditTimestampsAndStartsVersionAtZero() {
            Instant before = Instant.now().minusSeconds(1);

            Payment loaded = saveAndReload(payment("PAY-2", "payer-1"));

            assertThat(loaded.getVersion()).isZero();
            assertThat(loaded.getCreatedAt()).isAfter(before);
            assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
        }

        @Test
        void bumpsVersionAndUpdatedAtWhenStatusChanges() {
            Payment loaded = saveAndReload(payment("PAY-3", "payer-1"));
            Instant firstUpdate = loaded.getUpdatedAt();

            loaded.changeStatus(PaymentStatus.PROCESSING, null, "test");
            Payment reloaded = saveAndReload(loaded);

            assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(reloaded.getVersion()).isEqualTo(1L);
            assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(firstUpdate);
        }

        @Test
        void rejectsADuplicateReference() {
            paymentRepository.saveAndFlush(payment("PAY-DUP", "payer-1"));

            assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment("PAY-DUP", "payer-2")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void staleUpdateFailsWithOptimisticLockingInsteadOfOverwriting() {
            UUID id = saveAndReload(payment("PAY-LOCK", "payer-1")).getId();
            entityManager.clear();

            Payment staleCopy = paymentRepository.findById(id).orElseThrow();
            entityManager.detach(staleCopy); // simulates a second request that read the old version

            Payment current = paymentRepository.findById(id).orElseThrow();
            current.changeStatus(PaymentStatus.PROCESSING, null, "first");
            paymentRepository.saveAndFlush(current);

            staleCopy.changeStatus(PaymentStatus.CANCELLED, null, "second");
            assertThatThrownBy(() -> paymentRepository.saveAndFlush(staleCopy))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
    }

    @Nested
    class Lookups {

        @Test
        void findsByIdempotencyKey() {
            Payment saved = paymentRepository.saveAndFlush(Payment.create("PAY-IK", "key-123", BigDecimal.TEN,
                    Currency.getInstance("USD"), PaymentMethod.WALLET, "payer-1", "payee-1", null));

            assertThat(paymentRepository.findByIdempotencyKey("key-123")).contains(saved);
            assertThat(paymentRepository.findByIdempotencyKey("other-key")).isEmpty();
        }

        @Test
        void reportsWhetherAReferenceExists() {
            paymentRepository.saveAndFlush(payment("PAY-EX", "payer-1"));

            assertThat(paymentRepository.existsByReference("PAY-EX")).isTrue();
            assertThat(paymentRepository.existsByReference("PAY-NOPE")).isFalse();
        }
    }

    @Nested
    class FilteredHistory {

        private Payment pendingForAlice;
        private Payment processingForAlice;
        private Payment pendingForBob;

        private void saveThreePayments() {
            pendingForAlice = payment("PAY-A1", "alice");
            processingForAlice = payment("PAY-A2", "alice");
            processingForAlice.changeStatus(PaymentStatus.PROCESSING, null, "test");
            pendingForBob = payment("PAY-B1", "bob");
            paymentRepository.saveAllAndFlush(java.util.List.of(pendingForAlice, processingForAlice, pendingForBob));
            entityManager.clear();
        }

        @Test
        void noFiltersReturnsEverything() {
            saveThreePayments();

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(PaymentFilter.none())))
                    .containsExactlyInAnyOrder(pendingForAlice, processingForAlice, pendingForBob);
        }

        @Test
        void filtersByStatus() {
            saveThreePayments();
            var filter = new PaymentFilter(PaymentStatus.PENDING, null, null, null);

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(filter)))
                    .containsExactlyInAnyOrder(pendingForAlice, pendingForBob);
        }

        @Test
        void filtersByPayer() {
            saveThreePayments();
            var filter = new PaymentFilter(null, "alice", null, null);

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(filter)))
                    .containsExactlyInAnyOrder(pendingForAlice, processingForAlice);
        }

        @Test
        void combinesFiltersWithAnd() {
            saveThreePayments();
            var filter = new PaymentFilter(PaymentStatus.PENDING, "alice", null, null);

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(filter)))
                    .containsExactly(pendingForAlice);
        }

        @Test
        void blankPayerIdIsIgnored() {
            saveThreePayments();
            var filter = new PaymentFilter(null, "  ", null, null);

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(filter))).hasSize(3);
        }

        @Test
        void filtersByCreationTimeWithInclusiveStartAndExclusiveEnd() {
            Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
            saveThreePayments();
            Instant end = Instant.now().plus(1, ChronoUnit.MINUTES);

            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(
                    new PaymentFilter(null, null, start, end)))).hasSize(3);
            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(
                    new PaymentFilter(null, null, end, null)))).isEmpty();
            assertThat(paymentRepository.findAll(PaymentSpecifications.matching(
                    new PaymentFilter(null, null, null, start)))).isEmpty();
        }

        @Test
        void pagesAndSortsResults() {
            saveThreePayments();

            Page<Payment> firstPage = paymentRepository.findAll(
                    PaymentSpecifications.matching(PaymentFilter.none()),
                    PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "reference")));

            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(firstPage.getTotalPages()).isEqualTo(2);
            assertThat(firstPage.getContent()).extracting(Payment::getReference)
                    .containsExactly("PAY-A1", "PAY-A2");
        }
    }
}
