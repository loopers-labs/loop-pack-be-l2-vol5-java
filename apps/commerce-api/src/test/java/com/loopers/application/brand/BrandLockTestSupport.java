package com.loopers.application.brand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

final class BrandLockTestSupport {

    static final long TIMEOUT_SECONDS = 10L;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    BrandLockTestSupport(
        DataSource dataSource, JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager
    ) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    void assertSharedLock(Long brandId) throws SQLException {
        assertThat(readBrandWithLock(brandId, "FOR SHARE")).isEqualTo(brandId);
        assertLockCannotBeAcquired(brandId, "FOR UPDATE");
    }

    void assertExclusiveLock(Long brandId) {
        assertLockCannotBeAcquired(brandId, "FOR SHARE");
    }

    private void assertLockCannotBeAcquired(Long brandId, String lockClause) {
        assertThatThrownBy(() -> readBrandWithLock(brandId, lockClause))
            .isInstanceOfSatisfying(SQLException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(3572)
            );
    }

    private Long readBrandWithLock(Long brandId, String lockClause) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM brands WHERE id = ? " + lockClause + " NOWAIT"
            )) {
                statement.setLong(1, brandId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : null;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    Future<?> submit(ExecutorService workers, Runnable operation) {
        return submit(workers, new AtomicLong(), new CountDownLatch(1), operation);
    }

    Future<?> submit(
        ExecutorService workers, AtomicLong connectionId, CountDownLatch started, Runnable operation
    ) {
        return workers.submit(() -> transaction.executeWithoutResult(status -> {
            connectionId.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
            started.countDown();
            operation.run();
        }));
    }

    void awaitLockWait(long connectionId, String tableName) throws SQLException {
        // 관찰용 connection으로 실제 대기 행을 확인한다. 업무 connection은 변경하지 않는다.
        try (Connection observer = DriverManager.getConnection(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root",
            System.getProperty("datasource.mysql-jpa.main.password")
        )) {
            await().alias(tableName + " 행의 잠금 대기").atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> hasLockWait(observer, connectionId, tableName));
        }
    }

    private boolean hasLockWait(Connection observer, long connectionId, String tableName) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("""
            SELECT COUNT(*)
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l
              ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads t ON t.THREAD_ID = w.REQUESTING_THREAD_ID
            WHERE t.PROCESSLIST_ID = ? AND l.OBJECT_NAME = ?
            """)) {
            statement.setLong(1, connectionId);
            statement.setString(2, tableName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) > 0;
            }
        }
    }

    void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)) {
                throw new AssertionError("트랜잭션 처리 대기 한도 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("트랜잭션 처리 대기 중 인터럽트", exception);
        }
    }

    void shutDown(ExecutorService workers) throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
}
