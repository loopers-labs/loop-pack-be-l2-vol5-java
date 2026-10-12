package com.loopers.infrastructure;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockLostUpdateControlIntegrationTest {

    private static final long INITIAL_STOCK = 5L;
    private static final long STALE_RESULT_STOCK = 4L;
    private static final int WORKER_COUNT = 2;
    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void reproducesLostUpdateWhenTwoTransactionsSaveTheirStaleCalculatedValue() throws Exception {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100_000L);
        product.changeStockTo(INITIAL_STOCK);
        Long productId = productRepository.save(product).getId();

        CountDownLatch bothReadsCompleted = new CountDownLatch(WORKER_COUNT);
        CountDownLatch allowWrites = new CountDownLatch(1);
        List<Long> observedStock = new CopyOnWriteArrayList<>();
        AtomicInteger committedTransactions = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);

        try {
            List<Future<Long>> attempts = List.of(
                submitStaleReadAndWrite(workers, productId, bothReadsCompleted, allowWrites,
                    observedStock, committedTransactions),
                submitStaleReadAndWrite(workers, productId, bothReadsCompleted, allowWrites,
                    observedStock, committedTransactions)
            );

            assertThat(bothReadsCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(observedStock).containsExactlyInAnyOrder(INITIAL_STOCK, INITIAL_STOCK);
            allowWrites.countDown();

            for (Future<Long> attempt : attempts) {
                assertThat(attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(INITIAL_STOCK);
            }

            long finalStock = new TransactionTemplate(transactionManager).execute(status ->
                jdbcTemplate.queryForObject(
                    "SELECT stock FROM products WHERE id = ?",
                    Long.class,
                    productId
                )
            );

            assertThat(committedTransactions.get()).isEqualTo(2);
            long expectedStockWithoutLostUpdate = INITIAL_STOCK - committedTransactions.get();
            assertThat(expectedStockWithoutLostUpdate).isEqualTo(3L);
            assertThat(finalStock).isEqualTo(STALE_RESULT_STOCK);
            assertThat(finalStock).isNotEqualTo(expectedStockWithoutLostUpdate);
        } finally {
            allowWrites.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<Long> submitStaleReadAndWrite(
        ExecutorService workers,
        Long productId,
        CountDownLatch bothReadsCompleted,
        CountDownLatch allowWrites,
        List<Long> observedStock,
        AtomicInteger committedTransactions
    ) {
        return workers.submit(() -> {
            Long observed = new TransactionTemplate(transactionManager).execute(status -> {
                Long stock = jdbcTemplate.queryForObject(
                    "SELECT stock FROM products WHERE id = ?",
                    Long.class,
                    productId
                );
                observedStock.add(stock);
                bothReadsCompleted.countDown();

                awaitWrites(allowWrites);

                // 두 요청이 재고 5에서 계산한 상수 4를 버전이나 현재 재고 조건 없이 저장한다.
                jdbcTemplate.update("UPDATE products SET stock = ? WHERE id = ?", STALE_RESULT_STOCK, productId);
                return stock;
            });
            committedTransactions.incrementAndGet();
            return observed;
        });
    }

    private void awaitWrites(CountDownLatch allowWrites) {
        try {
            if (!allowWrites.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release stale stock writes");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to release stale stock writes", exception);
        }
    }
}
