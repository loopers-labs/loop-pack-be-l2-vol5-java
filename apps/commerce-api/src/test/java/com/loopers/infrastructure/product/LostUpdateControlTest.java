package com.loopers.infrastructure.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.utils.DatabaseCleanUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 갱신 유실의 원인을 보여 주는 음성 대조군이다. 제품 코드를 거치지 않으며, 이 테스트의 통과는 실제 주문 정합성의 증거가 아니다.
@SpringBootTest
class LostUpdateControlTest {
    private static final int INITIAL_STOCK = 5;

    private static final long WAIT_SECONDS = 10;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private long productId;

    @BeforeEach
    void prepare() {
        long brandId = brandApplicationService.create("브랜드").id().value();
        productId = productApplicationService.create(brandId, "상품", 1000, INITIAL_STOCK).id();
    }

    @AfterEach
    void clean() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("두 트랜잭션이 잠금 없이 같은 재고 5를 읽고 각자 계산한 4를 저장하면 둘 다 성공해도 최종 재고는 4로 한 건이 유실된다")
    void reproducesLostUpdateWithoutLock() throws Exception {
        Map<Integer, Integer> readStocksByWorker = new ConcurrentHashMap<>();
        // 두 트랜잭션이 모두 5를 읽은 것을 확인한 뒤에만 장벽을 열어 쓰기를 허용한다. 확인에 실패하면 장벽이 깨져 쓰기 없이 끝난다.
        CyclicBarrier bothRead = new CyclicBarrier(2, () -> {
            if (readStocksByWorker.size() != 2
                || !readStocksByWorker.values().stream().allMatch(stock -> stock == INITIAL_STOCK)) {
                throw new IllegalStateException("두 트랜잭션이 모두 초기 재고를 읽지 못했습니다: " + readStocksByWorker);
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int worker = i;
                futures.add(executor.submit(() -> transactionTemplate.execute(status -> {
                    Integer readStock = jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class,
                        productId);
                    readStocksByWorker.put(worker, readStock);
                    awaitBarrier(bothRead);
                    int updatedRows = jdbcTemplate.update("update products set stock = ? where id = ?", readStock - 1, productId);
                    assertThat(updatedRows).isEqualTo(1);
                    return readStock;
                })));
            }
            List<Integer> readStocks = new ArrayList<>();
            for (Future<Integer> future : futures) {
                readStocks.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }

            int successCount = readStocks.size();
            int finalStock = productApplicationService.getAdminProduct(productId).stock();
            assertThat(readStocks).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
            assertThat(successCount).isEqualTo(2);
            assertThat(finalStock).isEqualTo(4);
            assertThat(successCount + finalStock).isNotEqualTo(INITIAL_STOCK);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 트랜잭션의 읽기 완료를 기다리지 못했습니다.", e);
        }
    }
}
