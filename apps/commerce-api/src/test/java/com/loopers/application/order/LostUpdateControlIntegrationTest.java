package com.loopers.application.order;

import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 갱신 유실 대조군. 운영 코드를 사용하지 않고, 테스트 안에서만 잠금 없는 읽기와 상수 쓰기를 재현한다.
 * 두 독립 트랜잭션이 같은 재고를 읽은 뒤(post-read 장벽) 각자 읽은 값에서 1을 뺀 상수를 조건·version 없이 저장한다.
 * 잘못된 업무 결과(성공 2건인데 재고는 1만 감소)를 기대값으로 assertion 하므로 JUnit 은 Green 이어야 한다.
 * 이 결과는 실제 주문 확정 정합성의 증거가 아니며, 대조군을 정상화하려고 운영 코드를 바꾸지 않는다.
 */
@DisplayName("[대조군] 잠금 없이 같은 재고를 읽고 상수로 저장하면, 두 차감이 모두 성공해도 재고는 1만 줄어든다.")
@SpringBootTest
class LostUpdateControlIntegrationTest {

    private static final long INITIAL_STOCK = 5L;
    private static final int WORKERS = 2;
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    /**
     * worker 종료를 실제로 확인한 경우에만 true 다. worker 실행 전과 종료 대기가 중단·초과된 경우는 미확인(false)으로 두어,
     * 살아 있는 트랜잭션과 겹치지 않도록 DB 정리를 하지 않는다.
     */
    private boolean workersTerminated;

    @AfterEach
    void tearDown() {
        if (workersTerminated) {
            databaseCleanUp.truncateAllTables();
        }
    }

    private record WorkerResult(long connectionId, String isolation, long readStock, int updateCount) {
    }

    @DisplayName("두 트랜잭션이 모두 5를 읽고 4를 저장해 commit 하면, 성공 2건·최종 재고 4로 초기 재고 − 성공 차감 수 = 최종 재고가 깨진다.")
    @Test
    void losesOneOfTwoCommittedDeductions() throws Exception {
        ProductModel product = productFixture.createProduct("운동화", 10_000L, INITIAL_STOCK);
        Long productId = product.getId();

        Queue<Long> readsBeforeWrite = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        // 두 읽기가 모두 초기 재고일 때만 쓰기를 허용한다. 아니면 장벽이 깨져 두 worker 모두 실패한다.
        CyclicBarrier postRead = new CyclicBarrier(WORKERS, () -> {
            if (!List.copyOf(readsBeforeWrite).equals(List.of(INITIAL_STOCK, INITIAL_STOCK))) {
                throw new IllegalStateException("쓰기 전 두 읽기가 모두 초기 재고가 아니다: " + readsBeforeWrite);
            }
        });

        List<WorkerResult> committed = new ArrayList<>();
        List<Throwable> technicalErrors = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<WorkerResult>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("시작 신호 대기 시간 초과");
                    }
                    return transactionTemplate.execute(status -> {
                        Long connectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
                        Long read = jdbcTemplate.queryForObject(
                            "SELECT stock_quantity FROM product WHERE id = ?", Long.class, productId);
                        readsBeforeWrite.add(read);
                        awaitPostRead(postRead);
                        int updateCount = jdbcTemplate.update(
                            "UPDATE product SET stock_quantity = ? WHERE id = ?", read - 1, productId);
                        return new WorkerResult(connectionId, isolation, read, updateCount);
                    });
                }));
            }

            assertThat(ready.await(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("worker 준비 시간 초과").isTrue();
            start.countDown();
            long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
            for (Future<WorkerResult> future : futures) {
                try {
                    committed.add(future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (ExecutionException e) {
                    technicalErrors.add(e.getCause());
                }
            }
        } finally {
            start.countDown();
            postRead.reset();
            executor.shutdownNow();
            workersTerminated = awaitWorkersTermination(executor);
        }
        assertThat(workersTerminated).as("worker 종료 미확인(시간 초과 또는 대기 중단): DB 정리를 하지 않았다").isTrue();

        long finalStock = jdbcTemplate.queryForObject(
            "SELECT stock_quantity FROM product WHERE id = ?", Long.class, productId);
        // 실행 근거 기록용: 각 worker 의 connection·격리 수준·읽은 값·UPDATE 반환 행 수(변경 행 수 0 도 실패로 세지 않는다).
        System.out.println("[O-T0] committed = " + committed + ", technicalErrors = " + technicalErrors
            + ", finalStock = " + finalStock);

        assertAll(
            () -> assertThat(technicalErrors).as("기술 오류").isEmpty(),
            () -> assertThat(committed).as("commit 성공").hasSize(WORKERS),
            () -> assertThat(committed).extracting(WorkerResult::readStock).containsOnly(INITIAL_STOCK),
            () -> assertThat(committed).extracting(WorkerResult::connectionId).doesNotHaveDuplicates(),
            () -> assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1),
            () -> assertThat(INITIAL_STOCK - committed.size()).as("초기 재고 − 성공 차감 수 = 최종 재고가 깨진다")
                .isNotEqualTo(finalStock)
        );
    }

    private static void awaitPostRead(CyclicBarrier barrier) {
        try {
            barrier.await(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("post-read 장벽 대기 중 중단", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("post-read 장벽 실패", e);
        }
    }

    /** 종료를 확인한 경우에만 true 를 돌려준다. 대기가 중단되면 인터럽트 상태를 복원하고 미확인(false)으로 둔다. */
    private static boolean awaitWorkersTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
