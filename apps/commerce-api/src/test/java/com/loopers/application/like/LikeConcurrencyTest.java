package com.loopers.application.like;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class LikeConcurrencyTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private LikeFacade likes;
    @Autowired private ProductFacade productFacade;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private LikeJpaRepository relationships;
    @Autowired private MockMvc mvc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void concurrentRepeatedRegistrationCreatesOneRelationshipAndKeepsHttpContract() throws Exception {
        long userId = registration.register().getId();
        long productId = product();
        List<Callable<Integer>> requests = IntStream.range(0, 8)
            .<Callable<Integer>>mapToObj(index -> () -> register(userId, productId)).toList();

        List<Integer> statuses = concurrently(requests);

        assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
        assertThat(statuses).filteredOn(code -> code == 200).hasSize(7);
        assertThat(relationships.findAll()).singleElement().satisfies(like -> {
            assertThat(like.getUserId()).isEqualTo(userId);
            assertThat(like.getProductId()).isEqualTo(productId);
        });
        assertThat(register(userId, productId)).isEqualTo(200);
    }

    @Test
    void concurrentRepeatedCancellationRemovesRelationshipAndReturnsNoContent() throws Exception {
        long userId = registration.register().getId();
        long productId = product();
        likes.register(userId, productId);
        List<Callable<Integer>> requests = IntStream.range(0, 8)
            .<Callable<Integer>>mapToObj(index -> () -> cancel(userId, productId)).toList();

        assertThat(concurrently(requests)).containsOnly(204).hasSize(8);
        assertThat(relationships.findAll()).isEmpty();
        assertThat(cancel(userId, productId)).isEqualTo(204);
    }

    @Test
    void differentUsersCanRegisterTheSameProduct() throws Exception {
        long first = registration.register().getId();
        long second = registration.register().getId();
        long productId = product();

        assertThat(concurrently(List.of(() -> register(first, productId), () -> register(second, productId))))
            .containsOnly(201).hasSize(2);
        assertThat(relationships.count()).isEqualTo(2);
        assertThat(relationships.countByProductIds(List.of(productId))).singleElement()
            .satisfies(row -> assertThat(((Number) row[1]).longValue()).isEqualTo(2));
    }

    @Test
    void relationshipInsertParticipatesInOuterTransactionRollback() {
        long userId = registration.register().getId();
        long productId = product();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(likes.register(userId, productId)).isTrue();
            assertThat(relationships.count()).isEqualTo(1);
            throw new IllegalStateException("failure after relationship SQL");
        })).isInstanceOf(IllegalStateException.class).hasMessage("failure after relationship SQL");
        assertThat(relationships.count()).isZero();
    }

    @Test
    void cancellationAfterUncommittedRegistrationLeavesNoRelationship() throws Exception {
        long userId = registration.register().getId();
        long productId = product();
        assertThat(waitForFirstCommit(() -> likes.register(userId, productId), () -> cancel(userId, productId)))
            .isEqualTo(204);
        assertThat(relationships.count()).isZero();
    }

    @Test
    void registrationAfterUncommittedCancellationCreatesRelationshipAgain() throws Exception {
        long userId = registration.register().getId();
        long productId = product();
        likes.register(userId, productId);
        assertThat(waitForFirstCommit(() -> likes.cancel(userId, productId), () -> register(userId, productId)))
            .isEqualTo(201);
        assertThat(relationships.count()).isEqualTo(1);
    }

    @Test
    void currentDeletionRaceKeepsHiddenRelationshipThatOwnerCanCancel() throws Exception {
        long userId = registration.register().getId();
        long productId = product();
        var executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(likes.register(userId, productId)).isTrue();
                // 등록 서비스 반환 후 커밋 전: 상품 삭제는 별도 연결에서 완료될 수 있다.
                var deletion = executor.submit(() -> productFacade.delete(productId));
                try {
                    deletion.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(relationships.count()).isEqualTo(1);
        assertThat(likes.getUserLikes(userId, userId)).isEmpty();
        assertThat(register(userId, productId)).isEqualTo(404);
        assertThat(cancel(userId, productId)).isEqualTo(204);
        assertThat(relationships.count()).isZero();
    }

    private int waitForFirstCommit(Runnable first, Callable<Integer> second) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        List<Future<Integer>> pending = new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                first.run();
                Future<Integer> future = executor.submit(() -> {
                    started.countDown();
                    return second.call();
                });
                pending.add(future);
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            return pending.getFirst().get(10, TimeUnit.SECONDS);
        } finally {
            pending.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int register(long userId, long productId) throws Exception {
        return mvc.perform(post("/api/v1/products/{id}/likes", productId).header("X-USER-ID", userId))
            .andReturn().getResponse().getStatus();
    }

    private int cancel(long userId, long productId) throws Exception {
        return mvc.perform(delete("/api/v1/products/{id}/likes", productId).header("X-USER-ID", userId))
            .andReturn().getResponse().getStatus();
    }

    private long product() {
        long brandId = brands.save(new BrandModel("brand", null)).getId();
        return products.save(new ProductModel(brandId, "product", 1_000L, 5)).getId();
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }
}
