package com.loopers.domain.payment;

import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentAttemptRepositoryTest {

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PaymentAttemptModel attempt(Long orderId, String attemptKey) {
        return new PaymentAttemptModel(orderId, 1L, attemptKey, "TEST", 7000L);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // 어떤 DB 제약이 막았는지까지 확인한다. 제약 위반 뒤의 세션에는 저장에 실패한 엔티티가 남아 있어, 정리의 flush가 깨지지 않게 비운다
    private void assertViolates(String constraintName, ThrowingCallable action) {
        assertThatThrownBy(action).hasStackTraceContaining(constraintName);
        entityManager.clear();
    }

    @DisplayName("결제 시도를 조회할 때, ")
    @Nested
    class Find {

        @DisplayName("저장한 시도는 flush/clear 후에도 주문 id·PG 주문 번호로 찾고, 상태·식별자·금액이 그대로다.")
        @Test
        void findsByOrderIdAndProviderOrderId_afterFlushAndClear() {
            // arrange
            PaymentAttemptModel saved = paymentAttemptRepository.save(attempt(42L, "key-42"));
            saved.applyCreate(new PgCreateResult.Accepted("TEST-TX-0001"));
            flushAndClear();

            // act
            Optional<PaymentAttemptModel> byOrder = paymentAttemptRepository.findByOrderId(42L);
            Optional<PaymentAttemptModel> byProvider = paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000042");

            // assert
            assertThat(byOrder).isPresent();
            assertThat(byOrder.get().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(byOrder.get().getTransactionKey()).isEqualTo("TEST-TX-0001");
            assertThat(byOrder.get().getAttemptKey()).isEqualTo("key-42");
            assertThat(byOrder.get().getAmount()).isEqualTo(7000L);
            assertThat(byProvider).isPresent();
            assertThat(byProvider.get().getOrderId()).isEqualTo(42L);
        }

        @DisplayName("없는 주문이나 PG 주문 번호는 빈 결과이고, 시도 키가 있는지는 키로만 확인한다.")
        @Test
        void returnsEmpty_whenNothingMatches() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-42"));

            // act & assert
            assertThat(paymentAttemptRepository.findByOrderId(99L)).isEmpty();
            assertThat(paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000099")).isEmpty();
            assertThat(paymentAttemptRepository.existsByAttemptKey("key-42")).isTrue();
            assertThat(paymentAttemptRepository.existsByAttemptKey("key-other")).isFalse();
        }

        @DisplayName("주문 id 여러 개로 한 번에 찾고, 빈 목록이면 쿼리 없이 빈 결과다.")
        @Test
        void findsAllByOrderIds() {
            // arrange
            paymentAttemptRepository.save(attempt(1L, "key-1"));
            paymentAttemptRepository.save(attempt(2L, "key-2"));
            paymentAttemptRepository.save(attempt(3L, "key-3"));
            flushAndClear();

            // act
            List<PaymentAttemptModel> found = paymentAttemptRepository.findAllByOrderIds(List.of(1L, 3L, 99L));

            // assert
            assertThat(found).extracting(PaymentAttemptModel::getOrderId).containsExactlyInAnyOrder(1L, 3L);
            assertThat(paymentAttemptRepository.findAllByOrderIds(List.of())).isEmpty();
        }

        @DisplayName("잠금 조회도 같은 시도를 돌려준다. 잠금은 트랜잭션 안에서만 가능하다.")
        @Test
        void findsByOrderIdForUpdate() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-42"));
            flushAndClear();

            // act
            Optional<PaymentAttemptModel> locked = paymentAttemptRepository.findByOrderIdForUpdate(42L);

            // assert
            assertThat(locked).isPresent();
            assertThat(locked.get().getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
            assertThat(paymentAttemptRepository.findByOrderIdForUpdate(99L)).isEmpty();
        }
    }

    @DisplayName("DB 유일 제약이, ")
    @Nested
    class UniqueConstraints {

        @DisplayName("한 주문에 두 번째 결제 시도를 저장하지 못하게 한다.")
        @Test
        void rejectsSecondAttemptOfSameOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-1"));
            entityManager.flush();

            // act & assert
            assertViolates("uk_payment_attempt_order", () -> {
                paymentAttemptRepository.save(attempt(42L, "key-2"));
                entityManager.flush();
            });
        }

        @DisplayName("다른 주문이 같은 시도 키를 쓰지 못하게 한다.")
        @Test
        void rejectsSameAttemptKeyOnAnotherOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(1L, "same-key"));
            entityManager.flush();

            // act & assert
            assertViolates("uk_payment_attempt_key", () -> {
                paymentAttemptRepository.save(attempt(2L, "same-key"));
                entityManager.flush();
            });
        }

        @DisplayName("다른 주문이 같은 PG 거래 키를 갖지 못하게 한다.")
        @Test
        void rejectsSameTransactionKeyOnAnotherOrder() {
            // arrange
            PaymentAttemptModel first = paymentAttemptRepository.save(attempt(1L, "key-1"));
            first.applyCreate(new PgCreateResult.Accepted("TEST-TX-0001"));
            entityManager.flush();
            PaymentAttemptModel second = paymentAttemptRepository.save(attempt(2L, "key-2"));
            second.applyCreate(new PgCreateResult.Accepted("TEST-TX-0001"));

            // act & assert
            assertViolates("uk_payment_attempt_transaction", entityManager::flush);
        }

        @DisplayName("거래 키가 아직 없는 시도는 여러 건이어도 허용한다. 거래가 없을 때 키를 꾸며 저장하지 않기 때문이다.")
        @Test
        void allowsManyAttemptsWithoutTransactionKey() {
            // arrange
            paymentAttemptRepository.save(attempt(1L, "key-1"));
            paymentAttemptRepository.save(attempt(2L, "key-2"));

            // act
            entityManager.flush();
            entityManager.clear();

            // assert
            assertThat(paymentAttemptRepository.findAllByOrderIds(List.of(1L, 2L)))
                .extracting(PaymentAttemptModel::getTransactionKey).containsOnlyNulls();
        }
    }
}
