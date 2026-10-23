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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Money AMOUNT = Money.of(6_000);

    private final PaymentRepository paymentRepository;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    PaymentRepositoryIntegrationTest(
        PaymentRepository paymentRepository,
        TransactionTemplate transactionTemplate,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentRepository = paymentRepository;
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
}
