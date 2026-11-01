package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.support.persistence.PurchaseActivityConstraintClassifier;
import com.loopers.testsupport.PurchaseActivityDbTestConfig;
import com.loopers.testsupport.PurchaseActivityDbTestConfig.ControllableProbe;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.loopers.testsupport.PurchaseActivityDbTestConfig.ACTIVITY;
import static com.loopers.testsupport.PurchaseActivityDbTestConfig.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C4: 같은 사건을 독립 TX·DB 연결 두 개로 동시에 처리한다. 승자를 commit 직전에 세운 채 패자의 완료 INSERT 가
 * 실제로 unique 잠금을 기다리는 것을 performance_schema 로 관찰한 뒤 승자를 commit·rollback 한다.
 * 잠금 관찰은 읽기 전용 root 연결로 한다(테스트 계정에는 조회 권한이 없다).
 */
@SpringBootTest(classes = PurchaseActivityDbTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("같은 사건의 동시 처리는 완료 unique 경쟁으로 하나만 반영하고, 패자는 rollback 뒤 새 TX 에서 같은 사실을 확인한다.")
class PurchaseActivityConcurrencyIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final OrderConfirmation EVENT = OrderConfirmation.normalize("OrderConfirmed", 1, "order-confirmed:42", 42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC),
        List.of(new OrderConfirmation.Item(7, 2), new OrderConfirmation.Item(8, 1), new OrderConfirmation.Item(9, 4)));
    private static final String WAITING_LOCKS = """
        SELECT l.ENGINE_TRANSACTION_ID AS trx, l.INDEX_NAME AS idx, l.LOCK_TYPE AS type, l.LOCK_MODE AS mode,
               l.LOCK_DATA AS data, w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking
        FROM performance_schema.data_locks l
        LEFT JOIN performance_schema.data_lock_waits w ON w.REQUESTING_ENGINE_LOCK_ID = l.ENGINE_LOCK_ID
        WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'processed_order_confirmation' AND l.LOCK_STATUS = 'WAITING'
        """;

    private static SingleConnectionDataSource observerSource;
    private static JdbcTemplate observer;

    @Autowired
    private PurchaseActivityHandler handler;
    @Autowired
    private PurchaseActivityTransactionService transactions;
    @Autowired
    private PurchaseActivityConstraintClassifier classifier;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch winnerPaused = new CountDownLatch(1);
    private final CountDownLatch releaseWinner = new CountDownLatch(1);

    @AfterAll
    static void closeObserver() {
        if (observerSource != null) {
            observerSource.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        // fixture 의 JDBC 속성은 컨텍스트 기동 뒤에 있으므로 첫 테스트에서 연다.
        if (observerSource == null) {
            observerSource = PurchaseActivityDbTestConfig.observer();
            observer = new JdbcTemplate(observerSource);
        }
        PurchaseActivityDbTestConfig.resetTables(jdbc);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseWinner.countDown();
        for (Thread thread : threads) {
            thread.join(DEADLINE.toMillis());
            assertThat(thread.isAlive()).as("작업 스레드가 끝나야 한다: " + thread.getName()).isFalse();
        }
        probe.reset();
    }

    private <T> FutureTask<T> start(String name, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread thread = new Thread(task, name);
        threads.add(thread);
        thread.start();
        return task;
    }

    /** "winner" 스레드만 commit 직전에 멈춘다. 풀린 뒤 rollbackWinner 면 주입 오류로 그 TX 를 rollback 시킨다. */
    private void pauseWinnerBeforeCommit(boolean rollbackWinner) {
        probe.onBeforeCommit(orderId -> {
            if (!"winner".equals(Thread.currentThread().getName())) {
                return;
            }
            winnerPaused.countDown();
            await(releaseWinner);
            if (rollbackWinner) {
                throw new IllegalStateException("주입: 승자 TX rollback");
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("장벽 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 패자의 완료 INSERT 가 unique 잠금을 기다리는 행을 deadline 안에 찾는다. 그동안 패자는 끝나지 않아야 한다. */
    private List<Map<String, Object>> awaitLoserWaiting(FutureTask<?> loser) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> waiting = observer.queryForList(WAITING_LOCKS);
            if (!waiting.isEmpty()) {
                assertThat(loser.isDone()).as("잠금 대기 중인 패자는 끝나지 않아야 한다").isFalse();
                System.out.println("[C4 OVERLAP] waiting=" + waiting);
                return waiting;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("패자의 잠금 대기를 관찰하지 못했습니다");
    }

    private Map<String, Long> counts() {
        return Map.of(
            "processed", jdbc.queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Long.class),
            "activity", jdbc.queryForObject("SELECT COUNT(*) FROM " + ACTIVITY, Long.class),
            "quantity", jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM " + ACTIVITY, Long.class));
    }

    private static <T extends Throwable> T causeOf(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        throw new AssertionError(type.getSimpleName() + " 원인이 없습니다", error);
    }

    @DisplayName("승자 commit → 패자 완료 INSERT 의 Spring 예외·Hibernate 원인·명명 제약 → 패자 rollback → 새 readOnly TX 의 동일 사실 확인")
    @Test
    void winnerCommitsLoserRollsBackThenVerifies() throws Exception {
        pauseWinnerBeforeCommit(false);
        AtomicReference<DataIntegrityViolationException> loserFailure = new AtomicReference<>();
        AtomicBoolean loserTxActiveAfterFailure = new AtomicBoolean(true);
        AtomicReference<Map<String, Long>> visibleToLoserAfterRollback = new AtomicReference<>();

        FutureTask<PurchaseActivityResult> winner = start("winner", () -> transactions.consume(EVENT));
        await(winnerPaused);
        FutureTask<PurchaseActivityResult> loser = start("loser", () -> {
            try {
                transactions.consume(EVENT);
                throw new AssertionError("패자는 완료 unique 위반으로 실패해야 한다");
            } catch (DataIntegrityViolationException failure) {
                loserFailure.set(failure);
                loserTxActiveAfterFailure.set(TransactionSynchronizationManager.isActualTransactionActive());
                visibleToLoserAfterRollback.set(counts());
                return transactions.verifyDuplicate(EVENT, failure);
            }
        });
        List<Map<String, Object>> waiting = awaitLoserWaiting(loser);
        releaseWinner.countDown();

        PurchaseActivityResult winnerResult = winner.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        PurchaseActivityResult loserResult = loser.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        DataIntegrityViolationException failure = loserFailure.get();
        ConstraintViolationException hibernate = causeOf(failure, ConstraintViolationException.class);
        SQLException sql = causeOf(failure, SQLException.class);
        List<String> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName());
        }
        System.out.println("[C4 CHAIN] " + chain + " constraint=" + hibernate.getConstraintName() + " kind=" + hibernate.getKind()
            + " sqlState=" + sql.getSQLState() + " errorCode=" + sql.getErrorCode()
            + " classified=" + classifier.completionUniqueViolation(failure));
        System.out.println("[C4 RESULT] winner=" + winnerResult + " loser=" + loserResult + " afterRollback=" + visibleToLoserAfterRollback.get()
            + " final=" + counts());

        assertAll(
            () -> assertThat(waiting).singleElement().satisfies(lock -> assertAll(
                () -> assertThat((String) lock.get("idx")).isIn("uk_poc_event", "uk_poc_order"),
                () -> assertThat(lock.get("blocking")).isNotNull())),
            () -> assertThat(winnerResult).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(loserResult).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED),
            () -> assertThat(failure).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(hibernate.getKind()).isEqualTo(ConstraintViolationException.ConstraintKind.UNIQUE),
            () -> assertThat(hibernate.getConstraintName()).isIn(
                "processed_order_confirmation.uk_poc_event", "processed_order_confirmation.uk_poc_order"),
            () -> assertThat(sql.getErrorCode()).isEqualTo(1062),
            () -> assertThat(classifier.completionUniqueViolation(failure)).isPresent(),
            () -> assertThat(loserTxActiveAfterFailure.get()).isFalse(),
            () -> assertThat(visibleToLoserAfterRollback.get()).isEqualTo(Map.of("processed", 1L, "activity", 3L, "quantity", 7L)),
            () -> assertThat(counts()).isEqualTo(Map.of("processed", 1L, "activity", 3L, "quantity", 7L))
        );
    }

    @DisplayName("handler 경로: 승자 APPLIED, 잠금을 기다린 패자는 rollback 뒤 재확인으로 ALREADY_PROCESSED, 효과는 한 번")
    @Test
    void handlerConvergesToSingleEffect() throws Exception {
        pauseWinnerBeforeCommit(false);

        FutureTask<PurchaseActivityResult> winner = start("winner", () -> handler.consume(EVENT));
        await(winnerPaused);
        FutureTask<PurchaseActivityResult> loser = start("loser", () -> handler.consume(EVENT));
        awaitLoserWaiting(loser);
        releaseWinner.countDown();

        assertAll(
            () -> assertThat(winner.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(loser.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED),
            () -> assertThat(counts()).isEqualTo(Map.of("processed", 1L, "activity", 3L, "quantity", 7L))
        );
    }

    @DisplayName("승자 rollback 이면 기다리던 다른 TX 가 정상 삽입해 APPLIED 이고 승자의 오류는 그대로 전파된다.")
    @Test
    void winnerRollsBackOtherInserts() throws Exception {
        pauseWinnerBeforeCommit(true);

        FutureTask<PurchaseActivityResult> winner = start("winner", () -> handler.consume(EVENT));
        await(winnerPaused);
        FutureTask<PurchaseActivityResult> loser = start("loser", () -> handler.consume(EVENT));
        awaitLoserWaiting(loser);
        releaseWinner.countDown();

        Throwable winnerError = catchThrowable(() -> winner.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
        PurchaseActivityResult loserResult = loser.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        System.out.println("[C4 WINNER-ROLLBACK] winnerError=" + winnerError.getCause() + " loser=" + loserResult + " final=" + counts());

        assertAll(
            () -> assertThat(winnerError).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("주입: 승자 TX rollback"),
            () -> assertThat(loserResult).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(counts()).isEqualTo(Map.of("processed", 1L, "activity", 3L, "quantity", 7L))
        );
    }

    @DisplayName("재확인에서 완료 기록이 없으면 원래 오류를 그대로 전파한다.")
    @Test
    void verifyWithoutCompletionRethrowsOriginal() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("original");

        assertAll(
            () -> assertThat(catchThrowable(() -> transactions.verifyDuplicate(EVENT, original))).isSameAs(original),
            () -> assertThat(counts()).isEqualTo(Map.of("processed", 0L, "activity", 0L, "quantity", 0L))
        );
    }
}
