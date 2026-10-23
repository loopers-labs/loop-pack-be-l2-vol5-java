package com.loopers.infrastructure.lock;

import com.loopers.domain.product.Product;
import com.loopers.support.concurrent.HeldRowLock;
import com.loopers.support.jpa.FailingStatementInspector;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PessimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잠금 동작을 실제 MySQL 8.0 으로 확인해 기록하는 <b>특성 테스트</b>다. 제품 코드의 정합성을
 * 검증하는 것이 아니라, 잠금 범위와 대기 한도에 관한 서술이 사실인지 고정한다. InnoDB 고유 동작이다.
 *
 * <p>앱 계정은 {@code performance_schema.data_locks} 를 읽을 수 없어, 잠금 범위는 별도 세션이 잠금 대기를 1초로 줄이고
 * 문장을 시도해 막히는지로 관찰한다. 별도 세션은 풀 밖의 연결이라 세션 변수가 풀로 새지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
    + "com.loopers.support.jpa.FailingStatementInspector")
class LockBehaviorCharacterizationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void seed() {
        // 브랜드 1~10 에 상품 20개씩. 옵티마이저가 브랜드 인덱스를 고르도록 통계를 갱신한다.
        for (int brand = 1; brand <= 10; brand++) {
            for (int i = 0; i < 20; i++) {
                jdbcTemplate.update("insert into products (brand_id, name, price, stock, created_at, updated_at) "
                    + "values (?, 'p', 1000, 10, current_timestamp(6), current_timestamp(6))", brand);
            }
        }
        jdbcTemplate.execute("analyze table products");
    }

    @AfterEach
    void tearDown() {
        FailingStatementInspector.disarm();
        databaseCleanUp.truncateAllTables();
    }

    // 앱과 같은 계정의 별도 세션이 잠금 대기를 1초로 줄이고 문장을 시도한다. 잠금 대기 초과(1205)이면 막힌 것이다.
    private boolean isBlocked(String sql) {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String user = System.getProperty("datasource.mysql-jpa.main.username");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("set session innodb_lock_wait_timeout = 1");
            statement.execute(sql);
            return false;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1205) {
                return true;
            }
            throw new AssertionError("잠금 대기 초과가 아닌 오류: " + e.getMessage(), e);
        }
    }

    private String insertForBrand(int brand) {
        return "insert into products (brand_id, name, price, stock, created_at, updated_at) "
            + "values (" + brand + ", 'n', 1000, 10, current_timestamp(6), current_timestamp(6))";
    }

    private HeldRowLock hold(String lockSql) {
        return HeldRowLock.acquire(transactionManager, jdbcTemplate, lockSql, new Object[0], null);
    }

    @DisplayName("브랜드 삭제의 상품 잠금 조회는")
    @Nested
    class BrandDeletionQuery {
        private static final String QUERY =
            "select id from products where brand_id = 5 and deleted_at is null order by id for update";

        @DisplayName("브랜드 인덱스를 타서 그 브랜드의 범위만 잠근다. 전체 테이블 스캔이면 모든 상품이 막힌다.")
        @Test
        void usesBrandIndex() {
            Map<String, Object> plan = jdbcTemplate.queryForList("explain " + QUERY).get(0);

            assertThat(plan).containsEntry("key", "idx_products_brand_price_id").containsEntry("type", "ref");
        }

        @DisplayName("같은 브랜드의 상품 수정·등록과 다음 브랜드의 등록은 막고, 직전·먼 브랜드와 일반 조회는 막지 않는다.")
        @Test
        void locksOwnBrandRangeAndNextNeighborGap() {
            try (HeldRowLock lock = hold(QUERY)) {
                assertThat(isBlocked("update products set stock = 9 where id = 81")).as("같은 브랜드 상품 수정").isTrue();
                assertThat(isBlocked(insertForBrand(5))).as("같은 브랜드 상품 등록").isTrue();
                // 인덱스가 (brand_id, deleted_at, price, id DESC)라 새 행(가장 큰 id)은 같은 브랜드·가격 행들 맨 앞에 놓인다.
                // 그래서 삭제가 잡은 브랜드 범위 바로 뒤의 갭(다음 브랜드 첫 레코드 앞)에 걸리고, 직전 브랜드의 등록은 걸리지 않는다.
                // 이전 (brand_id) 인덱스에서는 반대로 직전 브랜드 등록이 막혔다. 영향의 크기(이웃 한쪽, 삭제 한 건 동안)는 같다.
                assertThat(isBlocked(insertForBrand(6))).as("다음 브랜드 상품 등록(갭)").isTrue();

                assertThat(isBlocked("update products set stock = 9 where id = 101")).as("다른 브랜드 상품 수정").isFalse();
                assertThat(isBlocked(insertForBrand(4))).as("직전 브랜드 상품 등록").isFalse();
                assertThat(isBlocked(insertForBrand(1))).as("먼 브랜드 상품 등록").isFalse();
                assertThat(isBlocked("select count(*) from products where brand_id = 5")).as("일반 조회").isFalse();
            }
        }
    }

    @DisplayName("주문 확정의 상품 잠금 조회는")
    @Nested
    class ConfirmQuery {
        private static final String QUERY =
            "select id from products where id in (81, 82) and deleted_at is null order by id for update";

        @DisplayName("기본 키로 조회한 행만 잠그고, 인접한 상품과 등록, 일반 조회는 막지 않는다.")
        @Test
        void locksOnlyListedRows() {
            try (HeldRowLock lock = hold(QUERY)) {
                assertThat(isBlocked("update products set stock = 9 where id = 81")).as("잠근 상품 수정").isTrue();
                assertThat(isBlocked("update products set stock = 9 where id = 83")).as("인접 상품 수정").isFalse();
                assertThat(isBlocked(insertForBrand(5))).as("같은 브랜드 상품 등록").isFalse();
                assertThat(isBlocked("select stock from products where id = 81")).as("일반 조회").isFalse();
            }
        }
    }

    @DisplayName("잠금 대기 한도는")
    @Nested
    class LockWaitLimit {
        private static final long HOLD_MILLIS = 2_000;

        private long elapsedWhileHeld(Runnable action) {
            try (HeldRowLock lock = hold("select id from products where id = 1 for update")) {
                Thread releaser = new Thread(() -> {
                    try {
                        Thread.sleep(HOLD_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    lock.release();
                });
                releaser.start();
                long start = System.nanoTime();
                try {
                    action.run();
                } finally {
                    joinQuietly(releaser);
                }
                return (System.nanoTime() - start) / 1_000_000;
            }
        }

        private void joinQuietly(Thread thread) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void lockProduct(Map<String, Object> hints, String sessionSetup, String sessionReset) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (sessionSetup != null) {
                    jdbcTemplate.execute(sessionSetup);
                }
                try {
                    entityManager.find(Product.class, 1L, LockModeType.PESSIMISTIC_WRITE, hints);
                } finally {
                    if (sessionReset != null) {
                        jdbcTemplate.execute(sessionReset);
                    }
                }
            });
        }

        @DisplayName("JPA 표준 힌트 lock.timeout=0 이면 기다리지 않고 NOWAIT 로 즉시 실패한다.")
        @Test
        void hintZeroFailsImmediately() {
            FailingStatementInspector.record();

            long[] elapsed = new long[1];
            assertThatThrownBy(() -> elapsed[0] = elapsedWhileHeld(() -> lockProduct(Map.of("jakarta.persistence.lock.timeout", 0), null, null)))
                .isInstanceOf(LockTimeoutException.class);

            assertThat(FailingStatementInspector.statements()).anyMatch(sql -> sql.endsWith("for update nowait"));
        }

        @DisplayName("JPA 표준 힌트 lock.timeout=500 은 MySQL 에서 무시되어, 보유자가 풀 때까지 기다린다.")
        @Test
        void hintPositiveIsIgnored() {
            FailingStatementInspector.record();

            long elapsed = elapsedWhileHeld(() -> lockProduct(Map.of("jakarta.persistence.lock.timeout", 500), null, null));

            assertThat(elapsed).as("힌트 500ms 가 적용됐다면 약 0.5초에 실패했어야 한다").isGreaterThanOrEqualTo(HOLD_MILLIS - 300);
            assertThat(FailingStatementInspector.statements())
                .anyMatch(sql -> sql.endsWith("for update") && !sql.contains("wait") && !sql.contains("nowait"));
        }

        @DisplayName("세션 변수 innodb_lock_wait_timeout 을 줄이면 그 시간 뒤 PessimisticLockException 으로 실패한다.")
        @Test
        void sessionVariableBoundsTheWait() {
            long[] elapsed = new long[1];

            assertThatThrownBy(() -> elapsed[0] = elapsedWhileHeld(() -> lockProduct(Map.of(),
                "set session innodb_lock_wait_timeout = 1", "set session innodb_lock_wait_timeout = 50")))
                .isInstanceOf(PessimisticLockException.class)
                .hasRootCauseMessage("Lock wait timeout exceeded; try restarting transaction");
        }
    }
}
