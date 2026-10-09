package com.loopers.experiment;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 재고 차감 전략 비교 실험 (선택 확장, 3주차 설계 6.6). 제품 코드가 아니며 결과는 기술 글의 근거로 씀.
 *
 * <ul>
 *   <li>세 전략 모두 테스트 전용 테이블 experiment_stock 에서 같은 모양의 트랜잭션으로 실행함. 제품의 product 테이블과 코드는 건드리지 않음</li>
 *   <li>workMillis 는 확정 트랜잭션 안의 다른 작업 시간을 흉내 내는 실험 변수이며 이 실험 안에만 둠. 실제 서비스의 잠금 구간에는 넣지 않음</li>
 *   <li>시간 값은 매번 달라지므로 assertion 은 불변식에만 걺. 측정값은 build/experiment/stock-strategy.md 에 표로 남김</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*StockStrategyExperiment'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class StockStrategyExperiment {

    private static final long STOCK_ID = 1L;
    private static final int SCARCE_STOCK = 5;
    /** 모든 요청이 잠금을 쥐고 작업하게 해 대기열 · 커넥션 풀 고갈을 관찰하는 조건 */
    private static final int AMPLE_STOCK = 100;
    private static final long WAIT_SECONDS = 60;
    private static final long PROBE_INTERVAL_MILLIS = 50;
    private static final Path REPORT = Path.of("build", "experiment", "stock-strategy.md");

    private enum Strategy { PESSIMISTIC, OPTIMISTIC_RETRY, CONDITIONAL_UPDATE }

    private enum Result { SUCCESS, OUT_OF_STOCK, EXHAUSTED, LOCK_TIMEOUT, POOL_TIMEOUT, TECHNICAL }

    private record Outcome(Result result, int attempts) {}

    private record Probe(int requests, int failures, long maxMillis) {}

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 실제 OrderConfirmRetrier 와 같은 정책 (총 3회, 50ms 부터 지수 백오프 최대 200ms, 지터) */
    private final RetryTemplate retryTemplate = RetryTemplate.builder()
        .maxAttempts(3)
        .exponentialBackoff(50, 2, 200, true)
        .retryOn(OptimisticLockingFailureException.class)
        .build();

    @BeforeAll
    static void writeReportHeader() throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, String.join("\n",
            "# 재고 차감 전략 비교 (테스트 커넥션 풀 10, 커넥션 대기 한도 3초, 잠금 대기 한도 3초)",
            "",
            "| 전략 | 재고 | 요청 | 작업(ms) | 소요(ms) | 성공 | 품절 | 한도 초과 | 잠금 시간 초과 | 커넥션 대기 초과 | 기타 오류 | 총 시도 | 조회 요청 | 조회 실패 | 조회 최대(ms) |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
            ""
        ));
    }

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("create table if not exists experiment_stock "
                + "(id bigint primary key, stock int not null, version bigint not null)").executeUpdate();
        });
    }

    private void resetStock(int initialStock) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("delete from experiment_stock").executeUpdate();
            entityManager.createNativeQuery("insert into experiment_stock (id, stock, version) values (:id, :stock, 0)")
                .setParameter("id", STOCK_ID)
                .setParameter("stock", initialStock)
                .executeUpdate();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status ->
            entityManager.createNativeQuery("drop table if exists experiment_stock").executeUpdate()
        );
    }

    static Stream<Arguments> conditions() {
        List<Arguments> conditions = new ArrayList<>();
        for (int workMillis : new int[] {0, 100}) {
            for (int requests : new int[] {8, 12, 30}) {
                for (Strategy strategy : Strategy.values()) {
                    conditions.add(Arguments.of(strategy, SCARCE_STOCK, requests, workMillis));
                }
            }
        }
        for (int workMillis : new int[] {0, 100}) {
            for (Strategy strategy : Strategy.values()) {
                conditions.add(Arguments.of(strategy, AMPLE_STOCK, 30, workMillis));
            }
        }
        return conditions.stream();
    }

    @ParameterizedTest(name = "{0} · 재고 {1} · 요청 {2} · 작업 {3}ms")
    @MethodSource("conditions")
    void compare(Strategy strategy, int initialStock, int requests, int workMillis) throws Exception {
        // arrange
        resetStock(initialStock);

        // act
        ExecutorService executor = Executors.newFixedThreadPool(requests + 1);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        List<Outcome> outcomes = new ArrayList<>();
        Probe probe;
        long elapsedMillis;
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    return decrease(strategy, workMillis);
                }));
            }
            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            long startedAt = System.nanoTime();
            start.countDown();
            Future<Probe> probeFuture = executor.submit(() -> probeUnrelatedQueries(running));
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            running.set(false);
            probe = probeFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            running.set(false);
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        // report
        long succeeded = count(outcomes, Result.SUCCESS);
        int attempts = outcomes.stream().mapToInt(Outcome::attempts).sum();
        Files.writeString(REPORT, String.format("| %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d |%n",
            strategy, initialStock, requests, workMillis, elapsedMillis,
            succeeded, count(outcomes, Result.OUT_OF_STOCK), count(outcomes, Result.EXHAUSTED),
            count(outcomes, Result.LOCK_TIMEOUT), count(outcomes, Result.POOL_TIMEOUT), count(outcomes, Result.TECHNICAL),
            attempts, probe.requests(), probe.failures(), probe.maxMillis()
        ), StandardOpenOption.APPEND);

        // assert: 불변식만 확인함
        int finalStock = stock();
        assertAll(
            () -> assertThat(outcomes).hasSize(requests),
            () -> assertThat(finalStock).isGreaterThanOrEqualTo(0),
            () -> assertThat(initialStock - succeeded).isEqualTo(finalStock)
        );
    }

    private Outcome decrease(Strategy strategy, int workMillis) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            Result result = switch (strategy) {
                case PESSIMISTIC -> {
                    attempts.incrementAndGet();
                    yield pessimistic(workMillis);
                }
                case OPTIMISTIC_RETRY -> retryTemplate.execute(context -> {
                    attempts.incrementAndGet();
                    return optimistic(workMillis);
                });
                case CONDITIONAL_UPDATE -> {
                    attempts.incrementAndGet();
                    yield conditional(workMillis);
                }
            };
            return new Outcome(result, attempts.get());
        } catch (OptimisticLockingFailureException e) {
            return new Outcome(Result.EXHAUSTED, attempts.get());
        } catch (PessimisticLockingFailureException e) {
            return new Outcome(Result.LOCK_TIMEOUT, attempts.get());
        } catch (CannotCreateTransactionException e) {
            return new Outcome(Result.POOL_TIMEOUT, attempts.get());
        } catch (RuntimeException e) {
            return new Outcome(Result.TECHNICAL, attempts.get());
        }
    }

    /** 배타 잠금으로 읽고 판단한 뒤 작업하고 씀. 잠금은 commit 까지 유지됨 */
    private Result pessimistic(int workMillis) {
        return transactionTemplate.execute(status -> {
            int stock = ((Number) entityManager.createNativeQuery("select stock from experiment_stock where id = :id for update")
                .setParameter("id", STOCK_ID)
                .getSingleResult()).intValue();
            if (stock < 1) {
                return Result.OUT_OF_STOCK;
            }
            work(workMillis);
            entityManager.createNativeQuery("update experiment_stock set stock = :stock where id = :id")
                .setParameter("stock", stock - 1)
                .setParameter("id", STOCK_ID)
                .executeUpdate();
            return Result.SUCCESS;
        });
    }

    /** 잠금 없이 읽고 판단한 뒤 작업하고, 읽은 버전이 그대로일 때만 씀. 아니면 rollback 후 새 트랜잭션으로 다시 시도 */
    private Result optimistic(int workMillis) {
        return transactionTemplate.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("select stock, version from experiment_stock where id = :id")
                .setParameter("id", STOCK_ID)
                .getSingleResult();
            int stock = ((Number) row[0]).intValue();
            long version = ((Number) row[1]).longValue();
            if (stock < 1) {
                return Result.OUT_OF_STOCK;
            }
            work(workMillis);
            int updated = entityManager.createNativeQuery(
                    "update experiment_stock set stock = :stock, version = version + 1 where id = :id and version = :version")
                .setParameter("stock", stock - 1)
                .setParameter("id", STOCK_ID)
                .setParameter("version", version)
                .executeUpdate();
            if (updated == 0) {
                throw new OptimisticLockingFailureException("버전 충돌");
            }
            return Result.SUCCESS;
        });
    }

    /** 검사와 차감을 한 문장으로 함. 그 행의 잠금은 UPDATE 부터 commit 까지 유지되므로 작업은 차감 뒤에 둠 */
    private Result conditional(int workMillis) {
        return transactionTemplate.execute(status -> {
            int updated = entityManager.createNativeQuery(
                    "update experiment_stock set stock = stock - 1 where id = :id and stock >= 1")
                .setParameter("id", STOCK_ID)
                .executeUpdate();
            if (updated == 0) {
                return Result.OUT_OF_STOCK;
            }
            work(workMillis);
            return Result.SUCCESS;
        });
    }

    /** 실험이 도는 동안 무관한 조회를 계속 보내, 커넥션을 얻지 못해 실패하는지와 가장 오래 걸린 시간을 잼 */
    private Probe probeUnrelatedQueries(AtomicBoolean running) throws InterruptedException {
        int requests = 0;
        int failures = 0;
        long maxMillis = 0;
        while (running.get()) {
            long startedAt = System.nanoTime();
            try {
                transactionTemplate.execute(status -> entityManager.createNativeQuery("select 1").getSingleResult());
            } catch (RuntimeException e) {
                failures++;
            }
            requests++;
            maxMillis = Math.max(maxMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MILLIS);
        }
        return new Probe(requests, failures, maxMillis);
    }

    /** 확정 트랜잭션 안의 다른 작업 시간을 흉내 냄 (실험 변수) */
    private static void work(int workMillis) {
        if (workMillis == 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(workMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int stock() {
        return ((Number) transactionTemplate.execute(status ->
            entityManager.createNativeQuery("select stock from experiment_stock where id = :id")
                .setParameter("id", STOCK_ID)
                .getSingleResult()
        )).intValue();
    }

    private static long count(List<Outcome> outcomes, Result result) {
        return outcomes.stream().filter(outcome -> outcome.result() == result).count();
    }
}
