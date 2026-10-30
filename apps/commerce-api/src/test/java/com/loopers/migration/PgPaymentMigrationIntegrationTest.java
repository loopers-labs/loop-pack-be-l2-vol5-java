package com.loopers.migration;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.payment.BeginConfirmationResult;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.application.payment.PaymentTransactionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 설계 4.5 「스키마 타입과 기존 데이터 전환 검증」의 기존 데이터 보존 전환 재현.
 * 테스트 MySQL 컨테이너 안의 별도 스키마 두 개를 쓴다. FRESH 는 새 코드가 ddl-auto=create 로 만든 비교 기준이고,
 * LEGACY 는 과거 생성 DDL 의 orders·stock_history 와 FRESH 에서 복사한 나머지 테이블로 만든 전환 대상이다.
 * 이 재현의 통과는 로컬 loopers 등 실제 보존 대상 DB 의 전환 완료가 아니다.
 *
 * <p>FRESH 에는 6주차에 추가한 orders.confirmed_at 이 있다. 5주차 전환 SQL 과 보존 검증은 그대로 두고, 현재 스키마 비교와
 * validate 기동 직전에만 별도 6주차 열 추가 SQL 을 적용한다. 그래서 이 두 검증은 5주차 전환만이 아니라 「5주차 전환 + 6주차 열 추가」의 결과를 본다.
 */
@DisplayName("기존 POINT 데이터가 있는 스키마를 설계 3.6 순서로 전환하면 값이 보존되고 새 코드가 validate 로 기동한다.")
class PgPaymentMigrationIntegrationTest {

    private static final String FRESH = "pg_migration_fresh";
    private static final String LEGACY = "pg_migration_legacy";
    private static final String LEGACY_DDL = "db/pg-migration/legacy-orders-stock-history.sql";
    private static final String CONFIRMED_AT_SQL = "db/order-events/add-order-confirmed-at.sql";
    private static final Set<String> MIGRATED_TABLES = Set.of("orders", "stock_history", "payment", "payment_conflict");
    private static final String KEY = "123e4567-e89b-12d3-a456-426614174000";

    private static String serverUrl;

    private SingleConnectionDataSource rootDataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void createFreshSchema() throws Exception {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        serverUrl = url.substring(0, url.lastIndexOf('/'));
        recreateSchema(FRESH);
        try (ConfigurableApplicationContext ignored = boot(FRESH, "create")) {
            // 새 코드의 스키마 생성만 필요하다.
        }
    }

    @AfterAll
    static void dropSchemas() {
        withServer(server -> {
            server.execute("DROP DATABASE IF EXISTS " + FRESH);
            server.execute("DROP DATABASE IF EXISTS " + LEGACY);
        });
    }

    @BeforeEach
    void prepareLegacySchema() throws SQLException {
        recreateSchema(LEGACY);
        rootDataSource = new SingleConnectionDataSource(serverUrl + "/" + LEGACY, "root", "test", true);
        jdbc = new JdbcTemplate(rootDataSource);
        jdbc.execute("SET SESSION FOREIGN_KEY_CHECKS = 0");
        for (String table : tables(FRESH)) {
            if (!MIGRATED_TABLES.contains(table)) {
                String ddl = (String) jdbc.queryForMap("SHOW CREATE TABLE " + FRESH + "." + table).get("Create Table");
                jdbc.execute(ddl);
            }
        }
        ScriptUtils.executeSqlScript(connection(), new ClassPathResource(LEGACY_DDL));
        jdbc.execute("SET SESSION FOREIGN_KEY_CHECKS = 1");
        seedLegacyData();
    }

    @AfterEach
    void closeConnection() {
        rootDataSource.destroy();
    }

    @DisplayName("5주차 전환은 기존 행을 보존하고 과거 POINT 만 보정하며, 6주차 confirmed_at 추가 뒤에도 기존 행은 NULL·값 유지이고 스키마는 현재 코드와 같다.")
    @Test
    void migratesPreservingLegacyRows() throws SQLException {
        List<Map<String, Object>> ordersBefore = jdbc.queryForList("""
            SELECT id, user_id, status, order_total AS total, used_point_amount AS point, payment_amount AS paid,
                created_at, updated_at, deleted_at FROM orders ORDER BY id
            """);
        List<Map<String, Object>> itemsBefore = jdbc.queryForList("SELECT * FROM order_item ORDER BY id");
        List<Map<String, Object>> historiesBefore = jdbc.queryForList("SELECT * FROM stock_history ORDER BY id");
        List<Map<String, Object>> productsBefore = jdbc.queryForList("SELECT * FROM product ORDER BY id");
        List<Map<String, Object>> statusCountsBefore = statusCounts();
        List<Map<String, Object>> causeCountsBefore = causeCounts();

        List<String> violations = PgPaymentMigration.migrateIfPrecheckPasses(connection(), jdbc);

        assertThat(violations).isEmpty();
        assertAll(
            () -> assertThat(jdbc.queryForList("""
                SELECT id, user_id, status, total_amount AS total, point_amount AS point, paid_amount AS paid,
                    created_at, updated_at, deleted_at FROM orders ORDER BY id
                """)).isEqualTo(ordersBefore),
            () -> assertThat(jdbc.queryForList("SELECT * FROM order_item ORDER BY id")).isEqualTo(itemsBefore),
            () -> assertThat(jdbc.queryForList("SELECT * FROM stock_history ORDER BY id")).isEqualTo(historiesBefore),
            () -> assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(productsBefore),
            () -> assertThat(statusCounts()).isEqualTo(statusCountsBefore),
            () -> assertThat(causeCounts()).isEqualTo(causeCountsBefore),
            () -> assertThat(jdbc.queryForList("SELECT id, payment_method FROM orders ORDER BY id")).containsExactly(
                Map.of("id", 1L, "payment_method", "POINT"),
                Map.of("id", 2L, "payment_method", "POINT"),
                nullMethod(3L),
                nullMethod(4L)),
            () -> assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE status = 'DRAFT'
                  AND (payment_method IS NOT NULL OR point_amount IS NOT NULL OR paid_amount IS NOT NULL)
                """, Long.class)).isZero(),
            () -> assertThat(enumColumn("orders", "status")).isEqualTo(Map.of(
                "COLUMN_TYPE", "enum('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING')", "IS_NULLABLE", "NO")),
            () -> assertThat(enumColumn("stock_history", "cause")).isEqualTo(Map.of(
                "COLUMN_TYPE", "enum('ADMIN_CHANGE','ORDER_DEDUCTION','ORDER_RELEASE','ORDER_RESERVATION')", "IS_NULLABLE", "NO")),
            () -> assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                  AND ((TABLE_NAME = 'orders' AND COLUMN_NAME = 'status') OR (TABLE_NAME = 'stock_history' AND COLUMN_NAME = 'cause'))
                  AND COLUMN_DEFAULT IS NOT NULL
                """, Long.class)).isZero(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isZero(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict", Long.class)).isZero()
        );

        List<Map<String, Object>> ordersAfterWeek5 = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
        List<List<Map<String, Object>>> otherRowsAfterWeek5 = otherTableRows();
        addConfirmedAtColumn();

        assertAll("6주차 confirmed_at 열 추가",
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE confirmed_at IS NOT NULL", Long.class))
                .as("backfill 없이 기존 행은 모두 NULL").isZero(),
            () -> assertThat(withoutConfirmedAt(jdbc.queryForList("SELECT * FROM orders ORDER BY id")))
                .as("기존 열 값·건수 유지").isEqualTo(ordersAfterWeek5),
            () -> assertThat(otherTableRows()).isEqualTo(otherRowsAfterWeek5)
        );
        assertThat(tables(LEGACY)).isEqualTo(tables(FRESH));
        for (String table : MIGRATED_TABLES) {
            assertAll(table,
                () -> assertThat(columnDefinitions(LEGACY, table)).isEqualTo(columnDefinitions(FRESH, table)),
                () -> assertThat(indexDefinitions(LEGACY, table)).isEqualTo(indexDefinitions(FRESH, table)),
                () -> assertThat(checkDefinitions(LEGACY, table)).isEqualTo(checkDefinitions(FRESH, table))
            );
        }
    }

    @DisplayName("전환한 스키마는 새 상태·원인 값을 오류나 빈 문자열 대체 없이 저장하고, 목록 밖 값은 거절한다.")
    @Test
    void storesNewEnumValuesAfterMigration() throws SQLException {
        assertThat(PgPaymentMigration.migrateIfPrecheckPasses(connection(), jdbc)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class)).contains("STRICT_TRANS_TABLES");

        jdbc.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG', point_amount = 0 WHERE id = 3");
        String pending = jdbc.queryForObject("SELECT status FROM orders WHERE id = 3", String.class);
        jdbc.update("UPDATE orders SET status = 'PAYMENT_FAILED' WHERE id = 3");
        String failed = jdbc.queryForObject("SELECT status FROM orders WHERE id = 3", String.class);
        insertHistory(30L, "ORDER_RESERVATION", 3L);
        insertHistory(31L, "ORDER_RELEASE", 3L);

        assertAll(
            () -> assertThat(pending).isEqualTo("PAYMENT_PENDING"),
            () -> assertThat(failed).isEqualTo("PAYMENT_FAILED"),
            () -> assertThat(jdbc.queryForList("SELECT cause FROM stock_history WHERE id IN (30, 31) ORDER BY id", String.class))
                .containsExactly("ORDER_RESERVATION", "ORDER_RELEASE"),
            () -> assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'REFUNDED' WHERE id = 3"))
                .hasMessageContaining("Data truncated"),
            () -> assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 3", String.class)).isEqualTo("PAYMENT_FAILED")
        );
    }

    @DisplayName("사전 확인이 기대 정의·값과 다르면 전환 SQL 을 실행하지 않고 스키마·값을 그대로 둔다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("unexpectedStates")
    void stopsWhenPrecheckFails(String name, List<String> setup, String expectedViolation) throws SQLException {
        setup.forEach(jdbc::execute);
        List<Map<String, Object>> schemaBefore = schemaSnapshot();
        List<Map<String, Object>> ordersBefore = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
        List<Map<String, Object>> historiesBefore = jdbc.queryForList("SELECT * FROM stock_history ORDER BY id");

        List<String> violations = PgPaymentMigration.migrateIfPrecheckPasses(connection(), jdbc);

        assertAll(
            () -> assertThat(violations).anySatisfy(violation -> assertThat(violation).contains(expectedViolation)),
            () -> assertThat(schemaSnapshot()).isEqualTo(schemaBefore),
            () -> assertThat(jdbc.queryForList("SELECT * FROM orders ORDER BY id")).isEqualTo(ordersBefore),
            () -> assertThat(jdbc.queryForList("SELECT * FROM stock_history ORDER BY id")).isEqualTo(historiesBefore)
        );
    }

    static Stream<Arguments> unexpectedStates() {
        return Stream.of(
            Arguments.of("상태 ENUM 에 새 값이 일부 들어감",
                List.of("ALTER TABLE orders MODIFY COLUMN status ENUM('CONFIRMED','DRAFT','PAYMENT_PENDING') NOT NULL"),
                "기대 정의와 다름: orders.status"),
            Arguments.of("원인 열이 ENUM 이 아님",
                List.of("ALTER TABLE stock_history MODIFY COLUMN cause VARCHAR(32) NOT NULL"),
                "기대 정의와 다름: stock_history.cause"),
            Arguments.of("상태 열에 기본값이 있음",
                List.of("ALTER TABLE orders MODIFY COLUMN status ENUM('CONFIRMED','DRAFT') NOT NULL DEFAULT 'DRAFT'"),
                "NOT NULL·기본값 없음이 아님: orders.status"),
            Arguments.of("payment_method 가 이미 있음",
                List.of("ALTER TABLE orders ADD COLUMN payment_method VARCHAR(16) NULL"),
                "부분 전환 상태(새 열이 이미 있음): orders.payment_method"),
            Arguments.of("금액 열 이름이 이미 바뀜",
                List.of("ALTER TABLE orders RENAME COLUMN order_total TO total_amount"),
                "부분 전환 상태(새 열이 이미 있음): orders.total_amount"),
            Arguments.of("신규 테이블이 이미 있음",
                List.of("CREATE TABLE payment (id BIGINT NOT NULL PRIMARY KEY)"),
                "신규 테이블이 이미 있음: payment"),
            Arguments.of("상태에 빈 문자열이 저장됨",
                List.of("SET SESSION sql_mode = ''",
                    "INSERT INTO orders (id, user_id, status, order_total, created_at, updated_at) "
                        + "VALUES (9, 1, 'UNKNOWN_STATUS', 100, NOW(6), NOW(6))",
                    "SET SESSION sql_mode = DEFAULT"),
                "orders 의 목록 밖·빈 상태 값: 1건"),
            Arguments.of("CONFIRMED 금액이 전액 POINT 와 다름",
                List.of("UPDATE orders SET payment_amount = 1999 WHERE id = 1"),
                "전액 POINT 계약과 다른 CONFIRMED 금액: 1건"),
            Arguments.of("DRAFT 에 금액이 기록됨",
                List.of("UPDATE orders SET used_point_amount = 0 WHERE id = 3"),
                "금액이 기록된 DRAFT: 1건")
        );
    }

    @DisplayName("전환 전 스키마로는 새 코드가 validate 기동에 실패한다(대조군).")
    @Test
    void validateRejectsUnmigratedSchema() {
        assertThatThrownBy(() -> boot(LEGACY, "validate").close())
            .rootCause()
            .hasMessageContaining("Schema-validation");
    }

    @DisplayName("5주차 전환과 6주차 confirmed_at 추가를 거친 스키마에서 현재 코드는 validate 로 기동해 과거 POINT 주문을 읽고 신규 PG 결제를 시작한다.")
    @Test
    void bootsNewCodeWithValidateAfterMigration() throws SQLException {
        assertThat(PgPaymentMigration.migrateIfPrecheckPasses(connection(), jdbc)).isEmpty();
        addConfirmedAtColumn();

        try (ConfigurableApplicationContext context = boot(LEGACY, "validate")) {
            OrderFacade orderFacade = context.getBean(OrderFacade.class);
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            OrderInfo pastPoint = tx.execute(status -> orderFacade.getOrder(1L, 1L));
            OrderInfo draft = tx.execute(status -> orderFacade.getOrder(1L, 3L));

            BeginConfirmationResult begun = context.getBean(PaymentTransactionService.class)
                .beginConfirmation(1L, 3L, new PaymentConfirmCommand(KEY, "TEST", "0000-0000-0000-0000"));

            assertAll(
                () -> assertThat(pastPoint).isEqualTo(new OrderInfo(1L, 1L, "CONFIRMED", 2000L, 2000L, 2000L,
                    List.of(new OrderInfo.Item(1L, 2L, 1000L, 2000L)),
                    new OrderInfo.PaymentInfo("POINT", "SUCCESS", null, null, null))),
                () -> assertThat(draft.status()).isEqualTo("DRAFT"),
                () -> assertThat(draft.payment()).isNull(),
                () -> assertThat(begun.status()).isEqualTo(BeginConfirmationResult.Status.SEND)
            );
        }

        assertAll(
            () -> assertThat(jdbc.queryForMap("SELECT status, payment_method, point_amount, paid_amount FROM orders WHERE id = 3"))
                .isEqualTo(mapOf("status", "PAYMENT_PENDING", "payment_method", "PG", "point_amount", 0L, "paid_amount", null)),
            () -> assertThat(jdbc.queryForList(
                "SELECT product_id, changed_quantity FROM stock_history WHERE order_id = 3 AND cause = 'ORDER_RESERVATION' ORDER BY product_id"))
                .containsExactly(Map.of("product_id", 1L, "changed_quantity", 1L), Map.of("product_id", 2L, "changed_quantity", 1L)),
            () -> assertThat(jdbc.queryForList("SELECT id, stock_quantity FROM product ORDER BY id"))
                .containsExactly(Map.of("id", 1L, "stock_quantity", 9L), Map.of("id", 2L, "stock_quantity", 4L)),
            () -> assertThat(jdbc.queryForMap("SELECT order_id, attempt_key, amount, status, verification_status FROM payment"))
                .isEqualTo(Map.of("order_id", 3L, "attempt_key", KEY, "amount", 1500L, "status", "UNKNOWN",
                    "verification_status", "NOT_CHECKED")),
            () -> assertThat(jdbc.queryForMap("SELECT status, payment_method, total_amount, point_amount, paid_amount FROM orders WHERE id = 1"))
                .isEqualTo(Map.of("status", "CONFIRMED", "payment_method", "POINT", "total_amount", 2000L,
                    "point_amount", 2000L, "paid_amount", 2000L)),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE confirmed_at IS NOT NULL", Long.class))
                .as("POINT 확정·PG 대기 모두 확정 시각을 추정하지 않는다").isZero()
        );
    }

    private void seedLegacyData() {
        jdbc.update("INSERT INTO brand (id, name, created_at, updated_at) VALUES (1, 'legacy-brand', '2026-10-01 00:00:00', '2026-10-01 00:00:00')");
        jdbc.update("""
            INSERT INTO product (id, brand_id, name, price, stock_quantity, created_at, updated_at) VALUES
              (1, 1, 'legacy-a', 1000, 10, '2026-10-01 00:00:00', '2026-10-02 00:00:00'),
              (2, 1, 'legacy-b', 500, 5, '2026-10-01 00:00:00', '2026-10-02 00:00:00')
            """);
        jdbc.update("""
            INSERT INTO orders (id, user_id, status, order_total, used_point_amount, payment_amount, created_at, updated_at) VALUES
              (1, 1, 'CONFIRMED', 2000, 2000, 2000, '2026-10-02 00:00:00.123456', '2026-10-02 00:01:00'),
              (2, 2, 'CONFIRMED', 500, 500, 500, '2026-10-02 00:02:00', '2026-10-02 00:03:00'),
              (3, 1, 'DRAFT', 1500, NULL, NULL, '2026-10-03 00:00:00', '2026-10-03 00:00:00'),
              (4, 3, 'DRAFT', 1000, NULL, NULL, '2026-10-03 00:01:00', '2026-10-03 00:01:00')
            """);
        jdbc.update("""
            INSERT INTO order_item (id, order_id, product_id, quantity, unit_price, created_at, updated_at) VALUES
              (1, 1, 1, 2, 1000, '2026-10-02 00:00:00', '2026-10-02 00:00:00'),
              (2, 2, 2, 1, 500, '2026-10-02 00:02:00', '2026-10-02 00:02:00'),
              (3, 3, 1, 1, 1000, '2026-10-03 00:00:00', '2026-10-03 00:00:00'),
              (4, 3, 2, 1, 500, '2026-10-03 00:00:00', '2026-10-03 00:00:00'),
              (5, 4, 1, 1, 1000, '2026-10-03 00:01:00', '2026-10-03 00:01:00')
            """);
        jdbc.update("""
            INSERT INTO stock_history (id, product_id, order_id, cause, before_quantity, changed_quantity, after_quantity,
                created_at, updated_at) VALUES
              (1, 1, NULL, 'ADMIN_CHANGE', 0, 12, 12, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
              (2, 2, NULL, 'ADMIN_CHANGE', 0, 6, 6, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
              (3, 1, 1, 'ORDER_DEDUCTION', 12, 2, 10, '2026-10-02 00:01:00', '2026-10-02 00:01:00'),
              (4, 2, 2, 'ORDER_DEDUCTION', 6, 1, 5, '2026-10-02 00:03:00', '2026-10-02 00:03:00')
            """);
    }

    private void insertHistory(Long id, String cause, Long orderId) {
        jdbc.update("""
            INSERT INTO stock_history (id, product_id, order_id, cause, before_quantity, changed_quantity, after_quantity,
                created_at, updated_at) VALUES (?, 1, ?, ?, 10, 0, 10, NOW(6), NOW(6))
            """, id, orderId, cause);
    }

    private List<Map<String, Object>> statusCounts() {
        return jdbc.queryForList("SELECT CAST(status AS CHAR) AS value, COUNT(*) AS count FROM orders GROUP BY status ORDER BY value");
    }

    private List<Map<String, Object>> causeCounts() {
        return jdbc.queryForList("SELECT CAST(cause AS CHAR) AS value, COUNT(*) AS count FROM stock_history GROUP BY cause ORDER BY value");
    }

    private Map<String, Object> enumColumn(String table, String column) {
        return jdbc.queryForMap("""
            SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """, table, column);
    }

    private List<Map<String, Object>> schemaSnapshot() {
        return jdbc.queryForList("""
            SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION
            """);
    }

    /** 열 순서는 비교하지 않는다. 전환은 기존 열 위치를 유지하고 새 열을 끝에 붙인다. */
    private Set<String> columnDefinitions(String schema, String table) {
        return jdbc.queryForList("""
            SELECT CONCAT_WS('|', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, '<null>'),
                IFNULL(CHARACTER_SET_NAME, '-'), IFNULL(COLLATION_NAME, '-'), EXTRA)
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """, String.class, schema, table).stream().collect(Collectors.toSet());
    }

    private Set<String> indexDefinitions(String schema, String table) {
        return jdbc.queryForList("""
            SELECT CONCAT_WS('|', INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME)
            FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """, String.class, schema, table).stream().collect(Collectors.toSet());
    }

    private Set<String> checkDefinitions(String schema, String table) {
        return jdbc.queryForList("""
            SELECT CONCAT_WS('|', tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE)
            FROM information_schema.TABLE_CONSTRAINTS tc
            JOIN information_schema.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'
            """, String.class, schema, table).stream().collect(Collectors.toSet());
    }

    private Set<String> tables(String schema) {
        return Set.copyOf(jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", String.class, schema));
    }

    /** 5주차 전환과 별개인 6주차 열 추가 단계. */
    private void addConfirmedAtColumn() throws SQLException {
        ScriptUtils.executeSqlScript(connection(), new ClassPathResource(CONFIRMED_AT_SQL));
    }

    private static List<Map<String, Object>> withoutConfirmedAt(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("confirmed_at");
            return copy;
        }).toList();
    }

    private List<List<Map<String, Object>>> otherTableRows() {
        return List.of(
            jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            jdbc.queryForList("SELECT * FROM stock_history ORDER BY id"),
            jdbc.queryForList("SELECT * FROM product ORDER BY id")
        );
    }

    private Connection connection() throws SQLException {
        return rootDataSource.getConnection();
    }

    private static Map<String, Object> nullMethod(Long id) {
        return mapOf("id", id, "payment_method", null);
    }

    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void recreateSchema(String schema) {
        withServer(server -> {
            server.execute("DROP DATABASE IF EXISTS " + schema);
            server.execute("CREATE DATABASE " + schema);
            server.execute("GRANT ALL PRIVILEGES ON " + schema + ".* TO 'test'@'%'");
        });
    }

    private static void withServer(Consumer<JdbcTemplate> action) {
        SingleConnectionDataSource server = new SingleConnectionDataSource(serverUrl, "root", "test", true);
        try {
            action.accept(new JdbcTemplate(server));
        } finally {
            server.destroy();
        }
    }

    /** 커맨드라인 인자는 테스트 컨테이너 설정이 넣은 시스템 속성보다 우선한다. */
    private static ConfigurableApplicationContext boot(String schema, String ddlAuto) {
        return new SpringApplicationBuilder(CommerceApiApplication.class).run(
            "--server.port=0",
            "--management.server.port=0",
            "--datasource.mysql-jpa.main.jdbc-url=" + serverUrl + "/" + schema,
            "--spring.jpa.hibernate.ddl-auto=" + ddlAuto
        );
    }
}
