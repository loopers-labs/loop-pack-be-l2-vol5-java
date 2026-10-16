package com.loopers.application.productlike;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.productlike.ProductLikeRepository;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-36 3-6: 좋아요 수는 원자적 갱신으로 바뀌고, 관계를 실제로 만들거나 지웠을 때만 바뀐다 (INV-05).
 */
@SpringBootTest
class ProductLikeConcurrencyTest {

    private static final int THREADS = 10;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private ProductLikeFacade productLikeFacade;
    @Autowired
    private ProductLikeRepository productLikeRepository;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ProductModel product;

    @BeforeEach
    void setUp() {
        BrandModel brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 1);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[DR-36][INV-05] 서로 다른 사용자 10명이 같은 상품에 동시에 좋아요하면 좋아요 수는 10.")
    @Test
    void concurrentLike_differentUsers_countsAll() throws InterruptedException {
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            userIds.add(fixtures.user().getId());
        }

        List<Object> results = runConcurrently(i -> productLikeFacade.like(userIds.get(i), product.getId()));

        assertThat(results).containsOnly(SUCCESS);
        assertThat(userIds).allSatisfy(userId -> assertThat(productLikeRepository.find(userId, product.getId())).isPresent());
        assertThat(fixtures.reloadStat(product.getId()).getLikeCount()).isEqualTo(THREADS);
    }

    @DisplayName("[DR-36][INV-05][ASM-06] 같은 사용자가 같은 좋아요를 동시에 10번 취소하면 모두 성공하고 좋아요 수는 한 번만 줄어든다.")
    @Test
    void concurrentUnlike_samePair_decreasesOnce() throws InterruptedException {
        UserModel user = fixtures.user();
        UserModel other = fixtures.user();
        fixtures.like(user.getId(), product.getId());
        fixtures.like(other.getId(), product.getId());

        List<Object> results = runConcurrently(i -> productLikeFacade.unlike(user.getId(), product.getId()));

        assertThat(results).containsOnly(SUCCESS);
        assertThat(productLikeRepository.find(user.getId(), product.getId())).isEmpty();
        assertThat(fixtures.reloadStat(product.getId()).getLikeCount()).isEqualTo(1);
    }

    /**
     * THREADS 개 스레드가 출발 신호에 맞춰 동시에 task 를 실행한다.
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
                    task.run(index);
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
