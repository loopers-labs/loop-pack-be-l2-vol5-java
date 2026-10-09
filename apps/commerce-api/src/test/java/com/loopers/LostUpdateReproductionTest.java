package com.loopers;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 T-0 · 재현용 대조군 (C-7): 잠금 없이 "읽고 → 계산 → 쓰기"를 하면 두 주문이 모두 성공하고도 재고가 1개만 준다.
 * 운영 코드를 쓰지 않는 테스트 전용 SQL이다. 이 테스트의 통과는 갱신 유실이 재현된다는 뜻이지, 실제 서비스가 안전하다는 증거가 아니다.
 * 실제 서비스의 증거는 OrderConfirmConcurrencyTest다.
 */
@SpringBootTest
class LostUpdateReproductionTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("T-0 대조군 · 두 트랜잭션이 재고 5를 읽은 뒤 장벽을 풀고 각자 계산한 4를 저장하면, 성공 2건인데 최종 재고는 4다 (기대 3 — 갱신 유실)")
    @Test
    void reproducesLostUpdate_withoutLock() throws Exception {
        // arrange
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        Long productId = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5)).getId();
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> readStocks = Collections.synchronizedList(new ArrayList<>());
        int succeeded = 0;

        try {
            // act: 두 트랜잭션 모두 5를 읽은 것이 확실해진 다음에만 쓴다
            Runnable readThenWrite = () -> transactionTemplate.executeWithoutResult(status -> {
                Integer stock = jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
                readStocks.add(stock);
                awaitBarrier(bothRead);
                jdbcTemplate.update("UPDATE products SET stock = ? WHERE id = ?", stock - 1, productId);
            });
            Future<?> first = executor.submit(readThenWrite);
            Future<?> second = executor.submit(readThenWrite);
            first.get(10, TimeUnit.SECONDS);
            succeeded++;
            second.get(10, TimeUnit.SECONDS);
            succeeded++;
        } finally {
            bothRead.reset();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // assert: 두 트랜잭션 모두 5를 읽었고, 둘 다 예외 없이 commit했는데(get이 통과), 재고는 1개만 줄었다
        Integer finalStock = jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
        assertThat(readStocks).containsExactly(5, 5);
        assertThat(succeeded).isEqualTo(2);
        assertThat(finalStock).isEqualTo(4);
        // 불변식 위반: 성공 수량 2 + 최종 재고 4 ≠ 초기 재고 5
        assertThat(succeeded + finalStock).isNotEqualTo(5);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 트랜잭션이 함께 읽지 못했습니다", e);
        }
    }
}
