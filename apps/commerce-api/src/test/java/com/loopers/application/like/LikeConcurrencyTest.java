package com.loopers.application.like;

import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-13 검증: 좋아요 등록을 동시에 시작시켜도 LIK-01·LIK-02가 지켜지는가.
 */
@SpringBootTest
class LikeConcurrencyTest {

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 요청 N개를 CountDownLatch로 한 번에 출발시키고, 각 요청이 예외 없이 끝났는지 돌려준다. */
    private List<Throwable> runConcurrently(int requests, IntFunction<Runnable> requestOf) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Runnable request = requestOf.apply(i);
            futures.add(executor.submit(() -> {
                start.await();
                request.run();
                return null;
            }));
        }
        start.countDown();

        List<Throwable> failures = futures.stream()
            .map(future -> {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    return (Throwable) null;
                } catch (Exception e) {
                    return (Throwable) e;
                }
            })
            .filter(failure -> failure != null)
            .toList();
        executor.shutdown();
        return failures;
    }

    @DisplayName("LIK-01·LIK-02 같은 사용자가 같은 상품에 좋아요 10번을 동시에 보내도 모두 성공하고, 관계는 1개다.")
    @Test
    void sameUserConcurrentLikesKeepOneRelation() throws Exception {
        // arrange
        ProductModel product = productJpaRepository.save(new ProductModel(1L, "에어맥스", 10_000, 5));

        // act
        List<Throwable> failures = runConcurrently(10, i -> () -> likeFacade.like(1L, product.getId()));

        // assert
        assertThat(failures).isEmpty();
        assertThat(likeJpaRepository.count()).isEqualTo(1);
    }

    @DisplayName("LIK-04 서로 다른 사용자 100명이 동시에 좋아요하면 좋아요는 100개다.")
    @Test
    void differentUsersConcurrentLikesAreAllCounted() throws Exception {
        // arrange
        ProductModel product = productJpaRepository.save(new ProductModel(1L, "에어맥스", 10_000, 5));

        // act
        List<Throwable> failures = runConcurrently(100, i -> () -> likeFacade.like((long) i + 1, product.getId()));

        // assert
        assertThat(failures).isEmpty();
        assertThat(likeJpaRepository.count()).isEqualTo(100);
    }
}
