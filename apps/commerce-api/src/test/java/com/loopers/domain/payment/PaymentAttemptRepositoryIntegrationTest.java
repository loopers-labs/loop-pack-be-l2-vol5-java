package com.loopers.domain.payment;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 결제 시도 테이블이 DB 수준에서 지키는 규칙을 확인한다 — UNIQUE 두 개(docs/week5/design.md D-016)와
 * 결과 반영이 겹칠 때의 행 락(D-002, INV-002).
 */
@SpringBootTest
class PaymentAttemptRepositoryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static PaymentAttempt attempt(Long orderId, String attemptKey) {
        return new PaymentAttempt(orderId, attemptKey, String.format("LP-ORD-%010d", orderId), 7000L);
    }

    @DisplayName("UNIQUE 제약은, (D-016)")
    @Nested
    class UniqueConstraints {
        @DisplayName("같은 주문에 두 번째 결제 시도를 저장하면, DB가 거절한다. (주문당 한 시도)")
        @Test
        void rejectsSecondAttemptForSameOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-A"));

            // act & assert
            assertThrows(DataIntegrityViolationException.class,
                () -> paymentAttemptRepository.save(attempt(42L, "key-B")));
        }

        @DisplayName("같은 키를 다른 주문에 저장하면, DB가 거절한다. (주문 락으로는 막을 수 없는 D-015 ④)")
        @Test
        void rejectsSameKeyForDifferentOrder() {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-A"));

            // act & assert
            assertThrows(DataIntegrityViolationException.class,
                () -> paymentAttemptRepository.save(attempt(43L, "key-A")));
        }
    }

    @DisplayName("결제 시도를 찾을 때, ")
    @Nested
    class Find {
        @DisplayName("주문·키·providerOrderId·transactionKey로 같은 시도를 찾는다.")
        @Test
        void findsSameAttemptByEachIdentifier() {
            // arrange
            PaymentAttempt saved = attempt(42L, "key-A");
            saved.accept("TEST-TX-0042");
            paymentAttemptRepository.save(saved);

            // act & assert
            assertAll(
                () -> assertThat(paymentAttemptRepository.findByOrderId(42L)).get()
                    .extracting(PaymentAttempt::getAttemptKey).isEqualTo("key-A"),
                () -> assertThat(paymentAttemptRepository.findByAttemptKey("key-A")).get()
                    .extracting(PaymentAttempt::getOrderId).isEqualTo(42L),
                () -> assertThat(paymentAttemptRepository.findByProviderOrderId("LP-ORD-0000000042")).get()
                    .extracting(PaymentAttempt::getOrderId).isEqualTo(42L),
                () -> assertThat(paymentAttemptRepository.findByTransactionKey("TEST-TX-0042")).get()
                    .extracting(PaymentAttempt::getOrderId).isEqualTo(42L),
                () -> assertThat(paymentAttemptRepository.findByOrderId(99L)).isEmpty()
            );
        }
    }

    @DisplayName("결과 반영이 겹칠 때, (D-002, INV-002)")
    @Nested
    class LockForUpdate {
        @DisplayName("한 트랜잭션이 시도를 잠근 동안 다른 트랜잭션은 기다렸다가, commit된 최신 상태를 읽는다.")
        @Test
        void secondTransactionWaitsAndReadsCommittedStatus() throws Exception {
            // arrange
            paymentAttemptRepository.save(attempt(42L, "key-A"));
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);

            try {
                // act — 첫 번째 결과 반영이 시도를 잠그고 FAILED로 전이한 채 commit을 미룬다
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    PaymentAttempt locked = paymentAttemptRepository.findByOrderIdForUpdate(42L).orElseThrow();
                    locked.rejectBeforeCreate();
                    paymentAttemptRepository.save(locked);
                    lockHeld.countDown();
                    awaitOrFail(releaseLock);
                }));
                assertThat(lockHeld.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

                // 두 번째 결과 반영이 같은 시도를 잠그려 한다
                Future<ApplyResult> contender = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    PaymentAttempt locked = paymentAttemptRepository.findByOrderIdForUpdate(42L).orElseThrow();
                    return locked.rejectBeforeCreate();
                }));
                awaitUntilAnotherSessionWaitsOnPaymentAttempt();
                releaseLock.countDown();
                holder.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

                // assert — 두 번째는 첫 번째가 commit한 FAILED를 보고 효과 없이 끝난다(재고가 두 번 반환되지 않는다)
                assertThat(contender.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ApplyResult.NO_EFFECT);
                assertThat(paymentAttemptRepository.findByOrderId(42L)).get()
                    .extracting(PaymentAttempt::getStatus).isEqualTo(PaymentAttemptStatus.FAILED);
            } finally {
                releaseLock.countDown();
                executor.shutdownNow();
            }
        }
    }

    /**
     * payment_attempt를 건드리는 SQL이 실행 중인(=잠금 대기 중인) 다른 세션이 보일 때까지 기다린다.
     * ProductWritePathLockTest와 같은 방식이다 — 잠금을 쥔 세션은 commit을 미루고 있어 실행 중인 문장이 없다.
     */
    private void awaitUntilAnotherSessionWaitsOnPaymentAttempt() throws InterruptedException {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                    + "WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND INFO LIKE '%payment_attempt%'",
                Integer.class
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("결제 시도 행을 기다리는 세션이 " + TIMEOUT + " 안에 보이지 않았다");
            }
            // 관찰 가능한 DB 상태를 짧은 간격으로 다시 확인할 뿐, 실패가 우연히 나기를 기다리는 대기가 아니다.
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
