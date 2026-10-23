package com.loopers.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 설계 3.6 기존 데이터 보존 전환의 사전 확인(1·2)과 전환 SQL(3~5) 실행.
 * 사전 확인이 하나라도 어긋나면 전환 SQL 을 실행하지 않고 어긋난 내용만 돌려준다. 값·열을 고치지 않는다.
 */
final class PgPaymentMigration {

    static final String MIGRATION_SQL = "db/pg-migration/pg-payment-migration.sql";

    private static final String ORDERS_STATUS = "enum('CONFIRMED','DRAFT')";
    private static final String STOCK_CAUSE = "enum('ADMIN_CHANGE','ORDER_DEDUCTION')";
    private static final Set<String> LEGACY_ORDER_COLUMNS = Set.of("order_total", "used_point_amount", "payment_amount");
    private static final Set<String> NEW_ORDER_COLUMNS = Set.of("total_amount", "point_amount", "paid_amount", "payment_method");
    private static final Set<String> NEW_TABLES = Set.of("payment", "payment_conflict");

    private PgPaymentMigration() {
    }

    /** 사전 확인 통과 시에만 전환하고, 어긋난 내용을 돌려준다(빈 목록이면 전환함). */
    static List<String> migrateIfPrecheckPasses(Connection connection, JdbcTemplate jdbc) throws SQLException {
        List<String> violations = precheck(jdbc);
        if (violations.isEmpty()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION_SQL));
        }
        return violations;
    }

    static List<String> precheck(JdbcTemplate jdbc) {
        List<String> violations = new ArrayList<>();
        Set<String> tables = Set.copyOf(jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", String.class));
        NEW_TABLES.stream().filter(tables::contains).forEach(table -> violations.add("신규 테이블이 이미 있음: " + table));

        checkEnumColumn(jdbc, "orders", "status", ORDERS_STATUS, violations);
        checkEnumColumn(jdbc, "stock_history", "cause", STOCK_CAUSE, violations);

        Set<String> orderColumns = columnNames(jdbc, "orders");
        LEGACY_ORDER_COLUMNS.stream().filter(column -> !orderColumns.contains(column))
            .forEach(column -> violations.add("기존 금액 열이 없음: orders." + column));
        NEW_ORDER_COLUMNS.stream().filter(orderColumns::contains)
            .forEach(column -> violations.add("부분 전환 상태(새 열이 이미 있음): orders." + column));
        if (!violations.isEmpty()) {
            return violations;
        }

        countMustBeZero(jdbc, "orders 의 목록 밖·빈 상태 값",
            "SELECT COUNT(*) FROM orders WHERE status NOT IN ('CONFIRMED', 'DRAFT')", violations);
        countMustBeZero(jdbc, "stock_history 의 목록 밖·빈 원인 값",
            "SELECT COUNT(*) FROM stock_history WHERE cause NOT IN ('ADMIN_CHANGE', 'ORDER_DEDUCTION')", violations);
        countMustBeZero(jdbc, "전액 POINT 계약과 다른 CONFIRMED 금액", """
            SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED'
              AND (used_point_amount IS NULL OR payment_amount IS NULL
                OR used_point_amount <> order_total OR payment_amount <> order_total)
            """, violations);
        countMustBeZero(jdbc, "금액이 기록된 DRAFT",
            "SELECT COUNT(*) FROM orders WHERE status = 'DRAFT' AND (used_point_amount IS NOT NULL OR payment_amount IS NOT NULL)",
            violations);
        return violations;
    }

    private static void checkEnumColumn(JdbcTemplate jdbc, String table, String column, String expectedType,
                                        List<String> violations) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """, table, column);
        if (rows.isEmpty()) {
            violations.add("열이 없음: " + table + "." + column);
            return;
        }
        Map<String, Object> row = rows.get(0);
        if (!"enum".equals(row.get("DATA_TYPE")) || !expectedType.equals(row.get("COLUMN_TYPE"))) {
            violations.add("기대 정의와 다름: " + table + "." + column + " = " + row.get("COLUMN_TYPE"));
        }
        if (!"NO".equals(row.get("IS_NULLABLE")) || row.get("COLUMN_DEFAULT") != null) {
            violations.add("NOT NULL·기본값 없음이 아님: " + table + "." + column);
        }
    }

    private static Set<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
            """, String.class, table).stream().collect(Collectors.toUnmodifiableSet());
    }

    private static void countMustBeZero(JdbcTemplate jdbc, String description, String sql, List<String> violations) {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (count != null && count > 0) {
            violations.add(description + ": " + count + "건");
        }
    }
}
