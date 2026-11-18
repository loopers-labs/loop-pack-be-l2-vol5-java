package com.loopers.domain.promotion;

import com.loopers.infrastructure.promotion.IssuedCouponJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선착순 발급의 동시성 (DR-58). 실제 MySQL. 스레드마다 별도 트랜잭션으로 PromotionService.issue 를 동시에 부른다.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*CouponIssueConcurrencyTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class CouponIssueConcurrencyTest {

    private static final int THREADS = 20;
    private static final String SUCCESS = "SUCCESS";
    private static final ZonedDateTime STARTS_AT = ZonedDateTime.parse("2026-11-10T10:00:00+09:00[Asia/Seoul]");

    @Autowired
    private PromotionService promotionService;
    @Autowired
    private PromotionRepository promotionRepository;
    @Autowired
    private IssuedCouponJpaRepository issuedCouponJpaRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long createPromotion(int totalQuantity) {
        return transactionTemplate.execute(s -> promotionService.createCouponIssue(
            "선착순", STARTS_AT, STARTS_AT.plusDays(1), totalQuantity, 1, Map.of("discountValue", 1000)).promotion().getId());
    }

    @DisplayName("한도 10 에 서로 다른 사용자 20 명이 동시에 요청하면 정확히 10 장만 발급되고 나머지는 COUPON_SOLD_OUT, 남은 수량은 0 이다.")
    @Test
    void differentUsers_neverExceedTotalQuantity() throws InterruptedException {
        Long promotionId = createPromotion(10);

        List<Object> results = runConcurrently(index -> promotionService.issue(100L + index, promotionId));

        assertThat(results).filteredOn(SUCCESS::equals).hasSize(10);
        assertThat(results).filteredOn(ErrorType.COUPON_SOLD_OUT::equals).hasSize(10);
        assertThat(issuedCouponJpaRepository.findAll()).hasSize(10)
            .extracting(IssuedCouponModel::getUserId).doesNotHaveDuplicates();
        assertThat(promotionRepository.findStock(promotionId).orElseThrow().getRemaining()).isZero();
    }

    @DisplayName("같은 사용자가 20 번 동시에 요청하면 한 장만 발급되고 나머지는 COUPON_ALREADY_ISSUED, 수량은 1 만 줄어든다.")
    @Test
    void sameUser_receivesOnlyOne() throws InterruptedException {
        Long promotionId = createPromotion(10);

        List<Object> results = runConcurrently(index -> promotionService.issue(7L, promotionId));

        assertThat(results).filteredOn(SUCCESS::equals).hasSize(1);
        assertThat(results).filteredOn(ErrorType.COUPON_ALREADY_ISSUED::equals).hasSize(THREADS - 1);
        assertThat(issuedCouponJpaRepository.findAll()).hasSize(1);
        assertThat(promotionRepository.findStock(promotionId).orElseThrow().getRemaining()).isEqualTo(9);
    }

    /**
     * THREADS 개 스레드가 출발 신호에 맞춰 동시에 task 를 각자의 트랜잭션에서 실행한다.
     * 결과는 성공이면 SUCCESS, CoreException 이면 그 ErrorType, 그 외 예외면 예외 클래스 이름.
     */
    private List<Object> runConcurrently(IndexedTask task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Queue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionTemplate.executeWithoutResult(s -> task.run(index));
                    results.add(SUCCESS);
                } catch (CoreException e) {
                    results.add(e.getErrorType());
                } catch (Exception e) {
                    results.add(e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        return List.copyOf(results);
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }
}
