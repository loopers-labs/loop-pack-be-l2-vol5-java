package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentDiscrepancyRepository;
import com.loopers.domain.payment.PgTransaction;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Money AMOUNT = Money.of(6_000);

    private final PaymentRepository paymentRepository;
    private final PaymentDiscrepancyRepository paymentDiscrepancyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    PaymentRepositoryIntegrationTest(
        PaymentRepository paymentRepository,
        PaymentDiscrepancyRepository paymentDiscrepancyRepository,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentDiscrepancyRepository = paymentDiscrepancyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static Payment requested(long orderId, String attemptKey) {
        return Payment.request(orderId, PaymentAttemptKey.of(attemptKey), AMOUNT, CardType.TEST, NOW);
    }

    @Nested
    @DisplayName("PAY-010: 결제 시도는 주문과 따로 저장되고, 그대로 복원된다")
    class SaveAndRestore {

        @DisplayName("새 시도를 저장하면 id 가 붙고, 키로 찾으면 저장한 값 그대로다")
        @Test
        void restoresRequested() {
            Payment saved = paymentRepository.save(requested(42L, "attempt-1"));

            Payment found = paymentRepository.findByAttemptKey(PaymentAttemptKey.of("attempt-1")).orElseThrow();

            assertThat(saved.getId()).isNotNull();
            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getOrderId()).isEqualTo(42L);
            assertThat(found.getAttemptKey()).isEqualTo(PaymentAttemptKey.of("attempt-1"));
            assertThat(found.getProviderOrderId()).isEqualTo(ProviderOrderId.of(42L));
            assertThat(found.getAmount()).isEqualTo(AMOUNT);
            assertThat(found.getCardType()).isEqualTo(CardType.TEST);
            assertThat(found.getRequestedAt()).isEqualTo(NOW);
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(found.getTransactionKey()).isNull();
        }

        @DisplayName("주문 id 로도 찾는다")
        @Test
        void findsByOrderId() {
            paymentRepository.save(requested(42L, "attempt-1"));

            assertThat(paymentRepository.findByOrderId(42L))
                .map(Payment::getAttemptKey)
                .contains(PaymentAttemptKey.of("attempt-1"));
            assertThat(paymentRepository.findByOrderId(43L)).isEmpty();
        }

        @DisplayName("없는 키로 찾으면 비어 있다")
        @Test
        void emptyForUnknownKey() {
            assertThat(paymentRepository.findByAttemptKey(PaymentAttemptKey.of("nothing"))).isEmpty();
        }

        @DisplayName("전이한 시도를 다시 저장하면 상태와 거래 키가 바뀐 채로 읽힌다")
        @Test
        void persistsTransition() {
            Payment saved = paymentRepository.save(requested(42L, "attempt-1"));

            transactionTemplate.executeWithoutResult(status -> {
                Payment payment = paymentRepository.findByAttemptKey(PaymentAttemptKey.of("attempt-1")).orElseThrow();
                payment.observe(new CreateObservation.Accepted(new TransactionKey("TEST-TX-0042")));
                paymentRepository.save(payment);
            });

            Payment found = paymentRepository.findByAttemptKey(PaymentAttemptKey.of("attempt-1")).orElseThrow();
            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(found.getTransactionKey()).isEqualTo(new TransactionKey("TEST-TX-0042"));
        }
    }

    @Nested
    @DisplayName("PAY-001, PAY-002: 주문당 시도 하나, 키당 시도 하나를 DB 가 지킨다")
    class Uniqueness {

        @DisplayName("같은 주문에 다른 키로 두 번째 시도를 넣을 수 없다")
        @Test
        void rejectsSecondAttemptForSameOrder() {
            paymentRepository.save(requested(42L, "attempt-1"));

            assertThatThrownBy(() -> paymentRepository.save(requested(42L, "attempt-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @DisplayName("같은 키를 다른 주문에 쓸 수 없다")
        @Test
        void rejectsSameKeyForOtherOrder() {
            paymentRepository.save(requested(42L, "attempt-1"));

            assertThatThrownBy(() -> paymentRepository.save(requested(43L, "attempt-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @DisplayName("order_id, attempt_key, provider_order_id 는 각각 유일 인덱스를 가진다")
        @Test
        void hasUniqueIndexes() {
            assertThat(uniqueColumns()).contains("order_id", "attempt_key", "provider_order_id");
        }
    }

    @DisplayName("PG-014: 카드 번호 열이 없다")
    @Test
    void hasNoCardNumberColumn() {
        assertThat(columns()).contains("card_type").noneMatch(column -> column.contains("card_no") || column.contains("card_number"));
    }

    @SuppressWarnings("unchecked")
    private List<String> columns() {
        return ((List<Object>) entityManager.createNativeQuery("""
                select column_name from information_schema.columns
                where table_schema = database() and table_name = 'payment'
                """).getResultList()).stream().map(column -> column.toString().toLowerCase()).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> uniqueColumns() {
        return ((List<Object>) entityManager.createNativeQuery("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'payment'
                  and non_unique = 0 and index_name <> 'PRIMARY' and seq_in_index = 1
                """).getResultList()).stream().map(column -> column.toString().toLowerCase()).toList();
    }

    @Nested
    @DisplayName("PAY-017: 불일치 기록은 관찰 하나에 한 줄이다")
    class Discrepancy {

        private static final Long PAYMENT = 7L;
        private static final TransactionKey OBSERVED_KEY = new TransactionKey("TEST-TX-0001");

        private void record(PaymentDiscrepancy discrepancy) {
            transactionTemplate.executeWithoutResult(status ->
                paymentDiscrepancyRepository.record(PAYMENT, discrepancy, PaymentDiscrepancy.Source.LOOKUP, NOW));
        }

        private List<Map<String, Object>> rows() {
            return jdbcTemplate.queryForList("""
                select reason, source, observed_transaction_key, observed_status, observed_amount, observed_at
                from payment_discrepancy where payment_id = ? order by id
                """, PAYMENT);
        }

        @DisplayName("PAY-017: 불일치를 이유, 출처, PG 가 말한 키 · 상태 · 금액과 함께 남긴다")
        @Test
        void records() {
            record(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.AMOUNT_MISMATCH, OBSERVED_KEY, PgTransaction.Status.SUCCESS, Money.of(9_000)));

            assertThat(rows()).singleElement().satisfies(row -> {
                assertThat(row.get("reason")).isEqualTo("AMOUNT_MISMATCH");
                assertThat(row.get("source")).isEqualTo("LOOKUP");
                assertThat(row.get("observed_transaction_key")).isEqualTo("TEST-TX-0001");
                assertThat(row.get("observed_status")).isEqualTo("SUCCESS");
                assertThat(((Number) row.get("observed_amount")).longValue()).isEqualTo(9_000);
                assertThat(row.get("observed_at")).isNotNull();
            });
        }

        @DisplayName("PAY-017: 같은 관찰이 반복돼도 한 줄만 남는다. 같은 모순 callback 10 회")
        @Test
        void recordsSameObservationOnce() {
            PaymentDiscrepancy same = new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, OBSERVED_KEY, PgTransaction.Status.FAILED, Money.of(7_000));

            for (int i = 0; i < 10; i++) {
                record(same);
            }

            assertThat(rows()).hasSize(1);
        }

        @DisplayName("관찰한 상태가 다르면 다른 줄이다")
        @Test
        void recordsDifferentObservations() {
            record(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, OBSERVED_KEY, PgTransaction.Status.FAILED, Money.of(7_000)));
            record(new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED, OBSERVED_KEY, PgTransaction.Status.PENDING, Money.of(7_000)));

            assertThat(rows()).hasSize(2);
        }

        @DisplayName("관찰한 키가 없으면 빈 문자열로 남겨, 키 없는 관찰이 반복돼도 한 줄이다")
        @Test
        void recordsKeylessObservationOnce() {
            PaymentDiscrepancy keyless = new PaymentDiscrepancy(
                PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS, null, null, null);

            record(keyless);
            record(keyless);

            assertThat(rows()).singleElement().satisfies(row -> {
                assertThat(row.get("observed_transaction_key")).isEqualTo("");
                assertThat(row.get("observed_status")).isEqualTo("");
                assertThat(row.get("observed_amount")).isNull();
            });
        }

        @DisplayName("결제 · 이유 · 관찰한 키 · 관찰한 상태에 유일 인덱스가 있다")
        @Test
        void hasUniqueIndex() {
            List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'payment_discrepancy'
                  and non_unique = 0 and index_name <> 'PRIMARY'
                order by seq_in_index
                """, String.class).stream().map(String::toLowerCase).toList();

            assertThat(columns).containsExactly(
                "payment_id", "reason", "observed_transaction_key", "observed_status");
        }
    }
}
