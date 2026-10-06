package com.loopers.application.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
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

@SpringBootTest
class AdminStockLockTest {
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
        productId = productApplicationService.create(brandId, "상품", 1000, 5).id();
    }

    @AfterEach
    void clean() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("다른 트랜잭션이 상품을 삭제 처리하며 행을 잠근 동안 관리자 재고 설정은 잠금을 기다린 뒤 삭제 상태를 읽고 거절하며 삭제를 되돌리지 않는다")
    void adminStockChangeReadsProductOnlyAfterRowLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("update products set deleted = true where id = ?", productId);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> adminChange = executor.submit(() -> {
                try {
                    productApplicationService.setStock(productId, 9);
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            // 관리자 요청이 행 잠금 대기에 들어간 것을 확인한 즉시 삭제를 commit한다. 3초 잠금 대기 시간은 대기 진입 시점부터 흐른다.
            assertThat(awaitLockWait()).as("관리자 재고 설정이 행 잠금을 기다려야 한다").isTrue();
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(adminChange.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("삭제된 상품은 변경할 수 없습니다.");
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        ProductResult product = productApplicationService.getAdminProduct(productId);
        assertThat(product.deleted()).isTrue();
        assertThat(product.stock()).isEqualTo(5);
    }

    // 잠금 대기 중인 트랜잭션은 information_schema.innodb_trx에서 확인한다. 조회 권한이 필요해 테스트 컨테이너의 root 계정을 쓴다.
    private boolean awaitLockWait() throws SQLException, InterruptedException {
        String jdbcUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", password);
            Statement statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(
                    "select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'")) {
                    if (resultSet.next() && resultSet.getInt(1) > 0) {
                        return true;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
