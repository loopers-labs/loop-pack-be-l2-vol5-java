package com.loopers.infrastructure.purchaseactivity;

import com.loopers.application.purchaseactivity.PurchaseActivityHandler;
import com.loopers.application.purchaseactivity.PurchaseActivityResult;
import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmationModel;
import com.loopers.domain.purchaseactivity.PurchaseActivityRepository;
import com.loopers.testsupport.PurchaseActivityDbTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.loopers.testsupport.PurchaseActivityDbTestConfig.ACTIVITY;
import static com.loopers.testsupport.PurchaseActivityDbTestConfig.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C2: Entity 매핑이 실제 MySQL 에 만든 DDL 을 information_schema·SHOW CREATE TABLE 로 확인하고, 제약이 실제 쓰기를 막는지 본다.
 * Hibernate validate 로 대신하지 않는다.
 */
@SpringBootTest(classes = PurchaseActivityDbTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("구매 활동 두 테이블의 실제 DDL 은 소비 설계 3절과 같고 제약이 실제 쓰기를 막는다.")
class PurchaseActivitySchemaIntegrationTest {

    private static final String INSERT_PROCESSED = """
        INSERT INTO processed_order_confirmation
            (event_id, order_id, event_type, schema_version, confirmed_at, item_count, created_at, updated_at)
        VALUES (?, ?, 'OrderConfirmed', 1, '2026-10-28 07:00:00.123456', ?, NOW(6), NOW(6))
        """;
    private static final String INSERT_ACTIVITY = """
        INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
        VALUES (?, ?, ?, '2026-10-28 07:00:00.123456', NOW(6), NOW(6))
        """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PurchaseActivityHandler handler;
    @Autowired
    private PurchaseActivityRepository repository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        PurchaseActivityDbTestConfig.resetTables(jdbc);
    }

    record Column(String columnType, String nullable, String charset, String collation) {
    }

    private Map<String, Column> columns(String table) {
        Map<String, Column> columns = new LinkedHashMap<>();
        jdbc.query("""
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
            """, rs -> {
            columns.put(rs.getString(1), new Column(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        }, table);
        return columns;
    }

    /** 인덱스 이름 → "unique:열,열" 또는 "index:열,열". */
    private Map<String, String> indexes(String table) {
        Map<String, String> indexes = new LinkedHashMap<>();
        jdbc.query("""
            SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? GROUP BY INDEX_NAME, NON_UNIQUE
            """, rs -> {
            indexes.put(rs.getString(1), (rs.getInt(2) == 0 ? "unique:" : "index:") + rs.getString(3));
        }, table);
        return indexes;
    }

    private Map<String, String> checks(String table) {
        Map<String, String> checks = new LinkedHashMap<>();
        jdbc.query("""
            SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc
            JOIN information_schema.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'
            """, rs -> {
            checks.put(rs.getString(1), rs.getString(2).replace("`", ""));
        }, table);
        return checks;
    }

    private static SQLException sqlCause(DataAccessException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("SQLException 원인이 없습니다", error);
    }

    private SQLException rejected(String sql, Object... args) {
        DataAccessException error = catchThrowableOfType(DataAccessException.class, () -> jdbc.update(sql, args));
        assertThat(error).as("DB 가 거절해야 한다").isNotNull();
        return sqlCause(error);
    }

    @DisplayName("이 컨텍스트는 자기 두 테이블만 만들고 SHOW CREATE TABLE 결과를 남긴다.")
    @Test
    void ownsOnlyTwoTables() {
        List<String> tables = jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME", String.class);
        for (String table : List.of(PROCESSED, ACTIVITY)) {
            System.out.println("[C2 DDL] " + jdbc.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
        }
        System.out.println("[C2 DB] database=" + jdbc.queryForObject("SELECT DATABASE()", String.class)
            + " version=" + jdbc.queryForObject("SELECT VERSION()", String.class) + " tables=" + tables);

        assertThat(tables).containsExactly(PROCESSED, ACTIVITY);
    }

    @DisplayName("열 타입·nullable·ASCII binary collation·DATETIME(6) 이 표와 같고 상태·groupId·FK 열은 없다.")
    @Test
    void columnsMatchDesign() {
        Map<String, Column> processed = columns(PROCESSED);
        Map<String, Column> activity = columns(ACTIVITY);
        Integer foreignKeys = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN (?, ?)
            """, Integer.class, PROCESSED, ACTIVITY);

        assertAll(
            () -> assertThat(processed.keySet()).containsExactlyInAnyOrder("id", "created_at", "updated_at", "deleted_at",
                "event_id", "order_id", "event_type", "schema_version", "confirmed_at", "item_count"),
            () -> assertThat(processed.get("event_id")).isEqualTo(new Column("varchar(64)", "NO", "ascii", "ascii_bin")),
            () -> assertThat(processed.get("order_id")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(processed.get("event_type")).isEqualTo(new Column("varchar(32)", "NO", "ascii", "ascii_bin")),
            () -> assertThat(processed.get("schema_version")).isEqualTo(new Column("int", "NO", null, null)),
            () -> assertThat(processed.get("confirmed_at")).isEqualTo(new Column("datetime(6)", "NO", null, null)),
            () -> assertThat(processed.get("item_count")).isEqualTo(new Column("int", "NO", null, null)),
            () -> assertThat(processed.get("deleted_at").nullable()).isEqualTo("YES"),
            () -> assertThat(activity.keySet()).containsExactlyInAnyOrder("id", "created_at", "updated_at", "deleted_at",
                "order_id", "product_id", "quantity", "confirmed_at"),
            () -> assertThat(activity.get("order_id")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(activity.get("product_id")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(activity.get("quantity")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(activity.get("confirmed_at")).isEqualTo(new Column("datetime(6)", "NO", null, null)),
            () -> assertThat(foreignKeys).isZero()
        );
    }

    @DisplayName("명명 unique 세 개와 CHECK 다섯 개가 있다.")
    @Test
    void namedConstraints() {
        Map<String, String> processedIndexes = indexes(PROCESSED);
        Map<String, String> activityIndexes = indexes(ACTIVITY);
        System.out.println("[C2 INDEX] " + processedIndexes + " " + activityIndexes);
        System.out.println("[C2 CHECK] " + checks(PROCESSED) + " " + checks(ACTIVITY));

        assertAll(
            () -> assertThat(processedIndexes).containsOnly(Map.entry("PRIMARY", "unique:id"),
                Map.entry("uk_poc_event", "unique:event_id"), Map.entry("uk_poc_order", "unique:order_id")),
            () -> assertThat(activityIndexes).containsOnly(Map.entry("PRIMARY", "unique:id"),
                Map.entry("uk_ppa_order_product", "unique:order_id,product_id")),
            () -> assertThat(checks(PROCESSED)).containsOnly(
                Map.entry("ck_poc_order_id", "(order_id > 0)"), Map.entry("ck_poc_item_count", "(item_count > 0)")),
            () -> assertThat(checks(ACTIVITY)).containsOnly(Map.entry("ck_ppa_order_id", "(order_id > 0)"),
                Map.entry("ck_ppa_product_id", "(product_id > 0)"), Map.entry("ck_ppa_quantity", "(quantity > 0)"))
        );
    }

    @DisplayName("unique·CHECK·ASCII 열은 직접 SQL 쓰기도 막고, binary collation 은 대소문자를 다른 값으로 본다.")
    @Test
    void enforcesConstraints() {
        jdbc.update(INSERT_PROCESSED, "order-confirmed:42", 42L, 2);
        jdbc.update(INSERT_ACTIVITY, 42L, 7L, 2L);

        SQLException sameEvent = rejected(INSERT_PROCESSED, "order-confirmed:42", 43L, 1);
        SQLException sameOrder = rejected(INSERT_PROCESSED, "order-confirmed:44", 42L, 1);
        SQLException sameProduct = rejected(INSERT_ACTIVITY, 42L, 7L, 1L);
        List<SQLException> checkFailures = List.of(
            rejected(INSERT_PROCESSED, "order-confirmed:0", 0L, 1),
            rejected(INSERT_PROCESSED, "order-confirmed:45", 45L, 0),
            rejected(INSERT_ACTIVITY, 0L, 7L, 1L),
            rejected(INSERT_ACTIVITY, 46L, 0L, 1L),
            rejected(INSERT_ACTIVITY, 46L, 7L, 0L));
        SQLException nonAscii = rejected(INSERT_PROCESSED, "order-confirmed：47", 47L, 1);
        jdbc.update(INSERT_PROCESSED, "ORDER-CONFIRMED:42", 48L, 1);

        assertAll(
            () -> assertThat(sameEvent.getErrorCode()).isEqualTo(1062),
            () -> assertThat(sameEvent.getMessage()).contains("processed_order_confirmation.uk_poc_event"),
            () -> assertThat(sameOrder.getErrorCode()).isEqualTo(1062),
            () -> assertThat(sameOrder.getMessage()).contains("processed_order_confirmation.uk_poc_order"),
            () -> assertThat(sameProduct.getErrorCode()).isEqualTo(1062),
            () -> assertThat(sameProduct.getMessage()).contains("product_purchase_activity.uk_ppa_order_product"),
            () -> assertThat(checkFailures).extracting(SQLException::getErrorCode).containsOnly(3819),
            () -> assertThat(checkFailures).extracting(SQLException::getMessage).satisfiesExactly(
                m -> assertThat(m).contains("ck_poc_order_id"), m -> assertThat(m).contains("ck_poc_item_count"),
                m -> assertThat(m).contains("ck_ppa_order_id"), m -> assertThat(m).contains("ck_ppa_product_id"),
                m -> assertThat(m).contains("ck_ppa_quantity")),
            () -> assertThat(nonAscii.getErrorCode()).isEqualTo(1366),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Integer.class)).isEqualTo(2)
        );
    }

    @DisplayName("저장한 확정 시각은 UTC 벽시계 DATETIME(6) 이고, 새 영속성 컨텍스트에서 같은 순간·UTC zone 으로 다시 읽힌다.")
    @Test
    void utcMicrosRoundTrip() {
        ZonedDateTime at = ZonedDateTime.of(2026, 10, 29, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
        OrderConfirmation event = OrderConfirmation.normalize("OrderConfirmed", 1, "order-confirmed:42", 42L, at,
            List.of(new OrderConfirmation.Item(7, 2)));

        assertThat(handler.consume(event)).isEqualTo(PurchaseActivityResult.APPLIED);
        Map<String, Object> raw = jdbc.queryForMap("""
            SELECT CAST(p.confirmed_at AS CHAR) AS processed, CAST(a.confirmed_at AS CHAR) AS activity
            FROM processed_order_confirmation p JOIN product_purchase_activity a ON a.order_id = p.order_id
            """);
        ZonedDateTime reloaded = transactionTemplate.execute(status ->
            repository.findProcessedByEventIdOrOrderId("order-confirmed:42", 42L).get(0).getConfirmedAt());
        ZonedDateTime activityReloaded = transactionTemplate.execute(status ->
            repository.findActivitiesByOrderId(42L).get(0).getConfirmedAt());
        System.out.println("[C2 TIME] raw=" + raw + " reloaded=" + reloaded + " activity=" + activityReloaded);

        assertAll(
            () -> assertThat(raw).containsEntry("processed", "2026-10-29 01:02:03.123456")
                .containsEntry("activity", "2026-10-29 01:02:03.123456"),
            () -> assertThat(reloaded.toInstant()).isEqualTo(at.toInstant()),
            () -> assertThat(reloaded.getZone().normalized()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(OrderConfirmation.isUtcMicros(reloaded)).isTrue(),
            () -> assertThat(activityReloaded.toInstant()).isEqualTo(at.toInstant())
        );
    }

    @DisplayName("완료 기록 factory 는 정규화한 상품 종류 수를 itemCount 로 둔다.")
    @Test
    void itemCountIsNormalizedKinds() {
        OrderConfirmation event = OrderConfirmation.normalize("OrderConfirmed", 1, "order-confirmed:42", 42L,
            ZonedDateTime.of(2026, 10, 29, 1, 2, 3, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmation.Item(7, 1), new OrderConfirmation.Item(7, 1), new OrderConfirmation.Item(8, 1)));
        assertThat(ProcessedOrderConfirmationModel.completed(event).getItemCount()).isEqualTo(2);
    }
}
