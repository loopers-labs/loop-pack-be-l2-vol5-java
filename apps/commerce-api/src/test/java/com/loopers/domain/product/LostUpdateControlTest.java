package com.loopers.domain.product;

import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 음성 대조군 — 제품 구현이 아니라 "잠금 없이 읽고 계산한 값을 쓰면 왜 틀리는지"를 보여주는 테스트.
 * 두 트랜잭션이 커밋된 재고 5를 잠금 없는 SELECT로 읽고, 둘 다 읽은 것을 확인한 뒤 각자 상수 4를 저장한다.
 * 이 테스트가 통과했다는 사실은 실제 주문 서비스의 정합성 증거가 아니다 (그건 OrderConcurrencyTest).
 */
@SpringBootTest
class LostUpdateControlTest {

    private static final int INITIAL_STOCK = 5;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("두 트랜잭션이 잠금 없이 같은 재고 5를 읽고 각자 4를 쓰면, 둘 다 성공했는데 최종 재고는 4다 — 차감 하나가 유실된다.")
    @Test
    void reproducesLostUpdate_whenTwoTransactionsWriteValuesComputedFromUnlockedReads() throws Exception {
        // arrange — worker 시작 전에 commit
        Long productId = productJpaRepository.save(new ProductModel("에어맥스", 1000L, 1L, INITIAL_STOCK)).getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier bothRead = new CyclicBarrier(2);
        try {
            // act
            List<Future<Integer>> workers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                workers.add(executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    int read = ((Number) entityManager
                        .createNativeQuery("SELECT stock_remaining FROM product WHERE id = :id")
                        .setParameter("id", productId)
                        .getSingleResult()).intValue();
                    awaitBarrier(bothRead);
                    entityManager
                        .createNativeQuery("UPDATE product SET stock_remaining = :stock WHERE id = :id")
                        .setParameter("stock", read - 1)
                        .setParameter("id", productId)
                        .executeUpdate();
                    return read;
                })));
            }
            List<Integer> readValues = new ArrayList<>();
            for (Future<Integer> worker : workers) {
                readValues.add(worker.get(10, TimeUnit.SECONDS));
            }

            // assert — 두 transaction 모두 commit 성공, 둘 다 5를 읽었다
            int successCount = readValues.size();
            assertThat(readValues).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
            assertThat(successCount).isEqualTo(2);

            // assert — 성공 2건인데 최종 재고는 4: 초기 재고 − 성공 수 ≠ 최종 재고 (불변식 위반이 재현됨)
            int finalStock = productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
            assertThat(finalStock).isEqualTo(4);
            assertThat(INITIAL_STOCK - successCount).isNotEqualTo(finalStock);
        } finally {
            bothRead.reset();
            executor.shutdownNow();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 트랜잭션이 모두 읽기를 마치지 못했다", e);
        }
    }
}
