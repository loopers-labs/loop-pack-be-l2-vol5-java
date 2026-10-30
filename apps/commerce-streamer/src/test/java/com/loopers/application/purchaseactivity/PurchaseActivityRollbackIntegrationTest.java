package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import com.loopers.support.persistence.PurchaseActivityConstraintClassifier;
import com.loopers.testsupport.PurchaseActivityDbTestConfig;
import com.loopers.testsupport.PurchaseActivityDbTestConfig.ControllableProbe;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.loopers.testsupport.PurchaseActivityDbTestConfig.ACTIVITY;
import static com.loopers.testsupport.PurchaseActivityDbTestConfig.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C5: 실제 SQL 이 실행된 뒤의 중간 실패에서 완료 기록과 모든 활동이 함께 rollback 되는지, 완료 unique 가 아닌
 * DB 오류를 중복으로 바꾸지 않는지 본다. 미commit 행은 TX 밖의 READ UNCOMMITTED 관찰 연결(읽기 전용 root)로 확인한다.
 * commit 응답 유실처럼 결과 불명인 오류는 재현하지 않는다.
 */
@SpringBootTest(classes = PurchaseActivityDbTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("처리 TX 의 중간 실패는 완료·모든 활동을 함께 rollback 하고, 완료 unique 가 아닌 DB 오류는 그대로 전파한다.")
class PurchaseActivityRollbackIntegrationTest {

    private static final OrderConfirmation EVENT = OrderConfirmation.normalize("OrderConfirmed", 1, "order-confirmed:42", 42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC),
        List.of(new OrderConfirmation.Item(7, 2), new OrderConfirmation.Item(8, 1), new OrderConfirmation.Item(9, 4)));

    private static SingleConnectionDataSource observerSource;
    private static JdbcTemplate dirtyReader;

    @Autowired
    private PurchaseActivityHandler handler;
    @Autowired
    private PurchaseActivityConstraintClassifier classifier;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // fixture 의 JDBC 속성은 컨텍스트 기동 뒤에 있으므로 첫 테스트에서 연다.
        if (observerSource == null) {
            observerSource = PurchaseActivityDbTestConfig.observer();
            dirtyReader = new JdbcTemplate(observerSource);
            dirtyReader.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED");
        }
        PurchaseActivityDbTestConfig.resetTables(jdbc);
    }

    @AfterEach
    void tearDown() {
        probe.reset();
    }

    @AfterAll
    static void closeObserver() {
        if (observerSource != null) {
            observerSource.destroy();
        }
    }

    /** 커밋되지 않은 행까지 보는 (완료, 활동) 행 수. */
    private static List<Long> uncommitted() {
        return List.of(
            dirtyReader.queryForObject("SELECT COUNT(*) FROM " + PROCESSED + " WHERE order_id = 42", Long.class),
            dirtyReader.queryForObject("SELECT COUNT(*) FROM " + ACTIVITY + " WHERE order_id = 42", Long.class));
    }

    private List<Long> committed() {
        return List.of(
            jdbc.queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Long.class),
            jdbc.queryForObject("SELECT COUNT(*) FROM " + ACTIVITY, Long.class));
    }

    @DisplayName("두 번째 품목 전·뒤의 활동 INSERT 이후, 그리고 commit 직전 실패에서 이미 실행된 SQL 이 모두 rollback 된다.")
    @Test
    void midwayFailuresRollBackEverything() {
        IllegalStateException injected = new IllegalStateException("주입 실패");
        List<String> observed = new ArrayList<>();

        record Case(String name, Runnable arm, List<Long> expectedUncommitted) {
        }
        List<Case> cases = List.of(
            new Case("첫 품목 뒤·두 번째 품목 전", () -> probe.onAfterActivitySaved((orderId, productId) -> {
                if (productId == 7L) {
                    observed.add("first:" + uncommitted());
                    throw injected;
                }
            }), List.of(1L, 1L)),
            new Case("두 번째 품목 뒤", () -> probe.onAfterActivitySaved((orderId, productId) -> {
                if (productId == 8L) {
                    observed.add("second:" + uncommitted());
                    throw injected;
                }
            }), List.of(1L, 2L)),
            new Case("commit 직전", () -> probe.onBeforeCommit(orderId -> {
                observed.add("beforeCommit:" + uncommitted());
                throw injected;
            }), List.of(1L, 3L)));

        for (Case c : cases) {
            observed.clear();
            probe.reset();
            c.arm().run();
            Throwable error = catchThrowable(() -> handler.consume(EVENT));
            List<Long> after = committed();
            List<Long> afterDirty = uncommitted();
            System.out.println("[C5 ROLLBACK] case=" + c.name() + " inTx=" + observed + " committed=" + after + " dirtyAfter=" + afterDirty);
            assertAll(c.name(),
                () -> assertThat(error).isSameAs(injected),
                () -> assertThat(observed).singleElement().asString().endsWith(c.expectedUncommitted().toString()),
                () -> assertThat(after).containsExactly(0L, 0L),
                () -> assertThat(afterDirty).containsExactly(0L, 0L)
            );
        }

        probe.reset();
        assertAll(
            () -> assertThat(handler.consume(EVENT)).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(committed()).containsExactly(1L, 3L)
        );
    }

    @DisplayName("활동 unique 위반은 완료 unique 가 아니므로 재확인·no-op 없이 원래 예외로 전파하고 완료 INSERT 도 rollback 된다.")
    @Test
    void activityUniqueIsNotDuplicate() {
        // 첫 품목 INSERT 뒤 TX 에 묶이지 않은 다른 풀 연결(autocommit)이 (42, 8) 활동을 먼저 commit 한다.
        // 이 TX 의 두 번째 INSERT 가 활동 unique 에 걸린다.
        probe.onAfterActivitySaved((orderId, productId) -> {
            if (productId == 7L) {
                try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
                    statement.executeUpdate("""
                        INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
                        VALUES (42, 8, 1, '2026-10-28 07:00:00.123456', NOW(6), NOW(6))
                        """);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        Throwable error = catchThrowable(() -> handler.consume(EVENT));
        ConstraintViolationException hibernate = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException found) {
                hibernate = found;
            }
        }
        ConstraintViolationException violation = hibernate;
        List<Map<String, Object>> remaining = jdbc.queryForList("SELECT order_id, product_id FROM " + ACTIVITY);
        System.out.println("[C5 ACTIVITY-UNIQUE] error=" + error.getClass().getName() + " constraint="
            + (violation == null ? null : violation.getConstraintName()) + " classified=" + classifier.completionUniqueViolation(error)
            + " committed=" + committed() + " remaining=" + remaining);

        probe.reset();
        PurchaseActivityException orphan = catchThrowableOfType(PurchaseActivityException.class, () -> handler.consume(EVENT));
        assertAll(
            () -> assertThat(error).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(violation).isNotNull(),
            () -> assertThat(violation.getConstraintName()).isEqualTo("product_purchase_activity.uk_ppa_order_product"),
            () -> assertThat(classifier.completionUniqueViolation(error)).isEmpty(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Long.class)).isZero(),
            () -> assertThat(remaining).containsExactly(Map.of("order_id", 42L, "product_id", 8L)),
            () -> assertThat(orphan.getCode()).isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR)
        );
    }

    @DisplayName("실제 CHECK 위반의 Hibernate 예외도 완료 unique 로 식별하지 않는다.")
    @Test
    void checkViolationIsNotDuplicate() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO processed_order_confirmation
                    (event_id, order_id, event_type, schema_version, confirmed_at, item_count, created_at, updated_at)
                VALUES ('order-confirmed:42', 42, 'OrderConfirmed', 1, '2026-10-28 07:00:00', 0, NOW(6), NOW(6))
                """).executeUpdate());
        } catch (RuntimeException e) {
            failure.set(e);
        }
        Throwable error = failure.get();
        List<String> chain = new ArrayList<>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName() + (cause instanceof ConstraintViolationException c
                ? "[" + c.getKind() + ":" + c.getConstraintName() + "]" : ""));
        }
        System.out.println("[C5 CHECK] chain=" + chain + " classified=" + classifier.completionUniqueViolation(error));

        assertAll(
            () -> assertThat(error).isNotNull(),
            () -> assertThat(classifier.completionUniqueViolation(error)).isEmpty(),
            () -> assertThat(committed()).containsExactly(0L, 0L)
        );
    }

    @DisplayName("handler 를 상위 TX 안에서 부르면 쓰기 전에 거절한다.")
    @Test
    void rejectsOuterTransaction() {
        Throwable error = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> handler.consume(EVENT)));

        assertAll(
            () -> assertThat(error).isInstanceOf(IllegalStateException.class),
            () -> assertThat(committed()).containsExactly(0L, 0L)
        );
    }
}
