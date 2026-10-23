package com.loopers.infrastructure.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentAnomaly;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class PaymentAttemptRepositoryTest {

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PaymentAttempt attempt(long orderId, String attemptKey) {
        return PaymentAttempt.request(orderId, attemptKey, new Money(7_000));
    }

    @DisplayName("결제 시도를 저장하고 다시 읽을 때,")
    @Nested
    class SaveAndFind {
        @DisplayName("식별자·금액·상태가 DB 에서 그대로 읽히고, 거래 키는 null 이며 시각이 채워진다.")
        @Test
        void returnsSavedAttempt() {
            // arrange
            Long id = paymentAttemptRepository.save(attempt(42L, "attempt-1")).getId();

            // act
            PaymentAttempt found = paymentAttemptRepository.findByOrderId(42L).orElseThrow();

            // assert
            assertThat(found.getId()).isEqualTo(id);
            assertThat(found.getOrderId()).isEqualTo(42L);
            assertThat(found.getAttemptKey()).isEqualTo("attempt-1");
            assertThat(found.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
            assertThat(found.getAmount()).isEqualTo(new Money(7_000));
            assertThat(found.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
            assertThat(found.getTransactionKey()).isNull();
            assertThat(found.getAnomaly()).isNull();
            assertThat(found.getCreatedAt()).isNotNull();
        }

        @DisplayName("주문 id·시도 키·providerOrderId 로 각각 같은 시도를 찾고, 없으면 비어 있다.")
        @Test
        void findsByEachIdentifier() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "attempt-1"));

            // act & assert
            assertThat(paymentAttemptRepository.findByOrderId(42L)).isPresent();
            assertThat(paymentAttemptRepository.findByAttemptKey("attempt-1")).isPresent();
            assertThat(paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000042")).isPresent();
            assertThat(paymentAttemptRepository.findByOrderId(43L)).isEmpty();
            assertThat(paymentAttemptRepository.findByAttemptKey("other")).isEmpty();
            assertThat(paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000043")).isEmpty();
        }

        @DisplayName("상태 전이·거래 키·이상 징후가 갱신되어 읽힌다.")
        @Test
        void persistsTransitions() {
            // arrange
            PaymentAttempt saved = paymentAttemptRepository.save(attempt(42L, "attempt-1"));
            saved.markPending("TEST-TX-0042");
            saved.recordAnomaly(PaymentAnomaly.MULTIPLE_TRANSACTIONS);
            paymentAttemptRepository.save(saved);

            // act
            PaymentAttempt found = paymentAttemptRepository.findByOrderId(42L).orElseThrow();

            // assert
            assertThat(found.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(found.getTransactionKey()).isEqualTo("TEST-TX-0042");
            assertThat(found.getAnomaly()).isEqualTo(PaymentAnomaly.MULTIPLE_TRANSACTIONS);
        }

        @DisplayName("거래 키가 아직 없는 시도는 여러 건 저장할 수 있다 (null 은 유일 제약에 걸리지 않는다).")
        @Test
        void savesMultipleAttempts_withoutTransactionKey() {
            // act
            paymentAttemptRepository.save(attempt(1L, "attempt-1"));
            paymentAttemptRepository.save(attempt(2L, "attempt-2"));

            // assert
            assertThat(paymentAttemptRepository.findByOrderId(1L)).isPresent();
            assertThat(paymentAttemptRepository.findByOrderId(2L)).isPresent();
        }
    }

    @DisplayName("유일 제약을 어기는 시도를 저장할 때,")
    @Nested
    class UniqueConstraints {
        @DisplayName("같은 주문에 두 번째 시도를 저장할 수 없다 (주문당 한 시도).")
        @Test
        void rejectsSecondAttempt_forSameOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "attempt-1"));

            // act & assert
            assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentAttemptRepository.save(attempt(42L, "attempt-2"))
            );
            assertThat(paymentAttemptRepository.findByAttemptKey("attempt-2")).isEmpty();
        }

        @DisplayName("같은 시도 키를 다른 주문에 쓸 수 없다.")
        @Test
        void rejectsSameAttemptKey_forOtherOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "attempt-1"));

            // act & assert
            assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentAttemptRepository.save(attempt(43L, "attempt-1"))
            );
            assertThat(paymentAttemptRepository.findByOrderId(43L)).isEmpty();
        }

        @DisplayName("같은 거래 키를 두 시도가 가질 수 없다.")
        @Test
        void rejectsSameTransactionKey_forOtherAttempt() {
            // arrange
            PaymentAttempt first = attempt(42L, "attempt-1");
            first.markPending("TEST-TX-0042");
            paymentAttemptRepository.save(first);
            PaymentAttempt second = attempt(43L, "attempt-2");
            second.markPending("TEST-TX-0042");

            // act & assert
            assertThrows(DataIntegrityViolationException.class, () -> paymentAttemptRepository.save(second));
            assertThat(paymentAttemptRepository.findByOrderId(43L)).isEmpty();
        }
    }
}
