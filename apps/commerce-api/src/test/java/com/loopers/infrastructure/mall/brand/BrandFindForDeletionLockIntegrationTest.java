package com.loopers.infrastructure.mall.brand;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
// findForDeletion(@Lock)이 브랜드뿐 아니라 딸린 상품 행까지 실제로 잠그는지 검증
class BrandFindForDeletionLockIntegrationTest {
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("findForDeletion은 브랜드뿐 아니라 딸린 상품 행도 비관적 쓰기 잠금으로 보호한다")
    @Test
    void locksProductRows_notJustBrandRow() throws Exception {
        // arrange
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        long productId = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5)).getId();

        CountDownLatch findForDeletionLocked = new CountDownLatch(1);
        CountDownLatch probeDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        try {
            // act: 스레드 1 - findForDeletion으로 트랜잭션을 연 채 유지
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                brandRepository.findForDeletion(brand.getId());
                findForDeletionLocked.countDown();
                awaitQuietly(probeDone);
            }));

            // 스레드 2 - 별도 커넥션으로 같은 상품 행에 짧은 대기시간으로 잠금 조회 시도
            assertThat(findForDeletionLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> probe = executor.submit(() -> tryLockProductRowWithoutWaiting(productId));
            boolean acquiredWithoutWaiting = probe.get(5, TimeUnit.SECONDS);
            probeDone.countDown();
            holder.get(5, TimeUnit.SECONDS);

            // assert
            assertThat(acquiredWithoutWaiting)
                .as("findForDeletion이 상품 행까지 잠갔다면 별도 커넥션의 잠금 조회는 짧은 대기시간 안에 실패(타임아웃)해야 한다")
                .isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    // 별도 커넥션에서 SET innodb_lock_wait_timeout으로 짧게 대기시간을 준 뒤 FOR UPDATE 시도.
    // true = 대기 없이 즉시 잠금 획득(= 상품 행이 안 잠겨 있었음), false = 잠금 대기 타임아웃(= 상품 행이 잠겨 있었음)
    private boolean tryLockProductRowWithoutWaiting(long productId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement setTimeout = connection.prepareStatement("SET SESSION innodb_lock_wait_timeout = 1")) {
                setTimeout.execute();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM products WHERE id = ? FOR UPDATE")) {
                select.setLong(1, productId);
                select.executeQuery();
                connection.rollback();
                return true;
            } catch (SQLException lockWaitTimeout) {
                connection.rollback();
                if (lockWaitTimeout.getErrorCode() != 1205) {
                    throw lockWaitTimeout;
                }
                return false;
            }
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
