package com.loopers.infrastructure.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 갱신 유실을 재현하는 음성 대조군. 제품 구현이 아니며, 잠금 없이 읽고 계산한 값을 쓰면 왜 틀리는지 보여 줌 (3주차 설계 6.2).
 * 읽은 뒤의 장벽은 이 대조군 안에만 두고, 실제 서비스의 잠금 구간에는 넣지 않음.
 * 이 테스트의 통과는 실제 주문 정합성의 증거가 아님
 */
@SpringBootTest
class LostUpdateControlTest {

    private static final int INITIAL_STOCK = 5;
    private static final int WORKERS = 2;
    private static final long WAIT_SECONDS = 10;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("두 트랜잭션이 잠금 없이 같은 재고 5 를 읽고 각자 계산한 4 를 저장하면, 둘 다 성공하지만 최종 재고는 4 라 5 - 2 = 3 이 깨진다.")
    @Test
    void reproducesLostUpdate_whenTwoTransactionsWriteValuesComputedFromUnlockedRead() throws Exception {
        // arrange
        Long productId = persistProduct();
        CyclicBarrier bothRead = new CyclicBarrier(WORKERS);
        Callable<Integer> readThenWrite = () -> transactionTemplate.execute(status -> {
            int read = ((Number) entityManager.createNativeQuery("select stock from product where id = :id")
                .setParameter("id", productId)
                .getSingleResult()).intValue();
            awaitBothRead(bothRead);
            entityManager.createNativeQuery("update product set stock = :stock where id = :id")
                .setParameter("stock", read - 1)
                .setParameter("id", productId)
                .executeUpdate();
            return read;
        });

        // act
        List<Integer> reads = runTwice(readThenWrite);

        // assert
        int finalStock = stockOf(productId);
        int succeeded = reads.size();
        assertAll(
            () -> assertThat(reads).containsExactly(INITIAL_STOCK, INITIAL_STOCK),
            () -> assertThat(succeeded).isEqualTo(2),
            () -> assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1),
            () -> assertThat(INITIAL_STOCK - succeeded).isNotEqualTo(finalStock)
        );
    }

    private Long persistProduct() {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(INITIAL_STOCK);
            entityManager.persist(product);
            return product.getId();
        });
    }

    private int stockOf(Long productId) {
        Number stock = transactionTemplate.execute(status ->
            (Number) entityManager.createNativeQuery("select stock from product where id = :id")
                .setParameter("id", productId)
                .getSingleResult()
        );
        return stock.intValue();
    }

    /** 두 작업을 각자의 스레드 · 커넥션 · 트랜잭션에서 실행하고, 둘 다 commit 될 때까지 기다림 */
    private static List<Integer> runTwice(Callable<Integer> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                futures.add(executor.submit(task));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(WAIT_SECONDS * 3, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 두 트랜잭션이 모두 읽은 뒤에야 쓰기를 허용함. 시간 안에 모이지 않으면 재현 실패로 끝냄 */
    private static void awaitBothRead(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 트랜잭션이 모두 읽기 전에 장벽이 풀리지 않음", e);
        }
    }
}
