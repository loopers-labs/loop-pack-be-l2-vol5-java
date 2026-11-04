package com.loopers.infrastructure.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O2 DDL: 새 코드가 빈 테스트 MySQL 에 만든 order_confirmed_outbox 를 설계 W7.2-4 표와 비교한다.
 * Hibernate validate 대신 information_schema 와 SHOW CREATE TABLE 의 실제 결과를 본다.
 */
@SpringBootTest
@DisplayName("order_confirmed_outbox 의 열·명명 제약·인덱스는 설계 표와 같다.")
class OrderConfirmedOutboxSchemaIntegrationTest {

    private static final String TABLE = "order_confirmed_outbox";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("열은 표의 타입·길이·nullable·collation·DATETIME(6) 이고 열 집합에 추가 열이 없다.")
    @Test
    void columnsMatchDesignTable() {
        assertColumnsMatchDesignTable(jdbcTemplate);
    }

    /** 이 테스트와 O11(W6→W7 update 뒤 추가된 Outbox)이 같은 기대로 쓴다(구현 07). */
    static void assertColumnsMatchDesignTable(JdbcTemplate jdbcTemplate) {
        Map<String, Column> columns = columns(jdbcTemplate);
        System.out.println("[OUTBOX DDL] " + jdbcTemplate.queryForMap("SHOW CREATE TABLE " + TABLE).get("Create Table"));

        assertAll(
            () -> assertThat(columns.keySet()).containsExactlyInAnyOrder("id", "created_at", "updated_at", "deleted_at",
                "event_id", "order_id", "event_type", "schema_version", "message_key", "payload", "status", "attempt_count",
                "cycle_attempt_count", "next_attempt_at", "last_error", "published_at"),
            () -> assertThat(columns.get("event_id")).isEqualTo(new Column("varchar(64)", "NO", "ascii", "ascii_bin")),
            () -> assertThat(columns.get("order_id")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(columns.get("event_type")).isEqualTo(new Column("varchar(32)", "NO", "ascii", "ascii_bin")),
            () -> assertThat(columns.get("schema_version")).isEqualTo(new Column("int", "NO", null, null)),
            () -> assertThat(columns.get("message_key")).isEqualTo(new Column("varchar(20)", "NO", "ascii", "ascii_bin")),
            () -> assertThat(columns.get("payload")).isEqualTo(new Column("longtext", "NO", "utf8mb4", "utf8mb4_bin")),
            () -> assertThat(columns.get("status").columnType()).isEqualTo("varchar(16)"),
            () -> assertThat(columns.get("status").nullable()).isEqualTo("NO"),
            () -> assertThat(columns.get("attempt_count")).isEqualTo(new Column("bigint", "NO", null, null)),
            () -> assertThat(columns.get("cycle_attempt_count")).isEqualTo(new Column("int", "NO", null, null)),
            () -> assertThat(columns.get("next_attempt_at")).isEqualTo(new Column("datetime(6)", "NO", null, null)),
            () -> assertThat(columns.get("last_error").columnType()).isEqualTo("text"),
            () -> assertThat(columns.get("last_error").nullable()).isEqualTo("YES"),
            () -> assertThat(columns.get("published_at")).isEqualTo(new Column("datetime(6)", "YES", null, null)),
            () -> assertThat(columns.get("deleted_at").nullable()).isEqualTo("YES")
        );
    }

    @DisplayName("명명 unique·due 인덱스와 세 CHECK 만 있고, UNIQUE(order_id)·문자열 값 CHECK·물리 FK 는 없다.")
    @Test
    void namedConstraintsOnly() {
        assertNamedConstraintsOnly(jdbcTemplate);
    }

    /** 이 테스트와 O11 이 같은 기대로 쓴다(구현 07). */
    static void assertNamedConstraintsOnly(JdbcTemplate jdbcTemplate) {
        Map<String, String> indexes = indexes(jdbcTemplate);
        Map<String, String> checks = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc
            JOIN information_schema.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'
            """, rs -> {
            checks.put(rs.getString(1), rs.getString(2).replace("`", ""));
        }, TABLE);
        Integer foreignKeys = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'
            """, Integer.class, TABLE);
        System.out.println("[OUTBOX DDL] indexes=" + indexes + " checks=" + checks);

        assertAll(
            () -> assertThat(indexes).containsOnly(
                Map.entry("PRIMARY", "unique:id"),
                Map.entry("uk_oco_event", "unique:event_id"),
                Map.entry("idx_oco_due", "index:status,next_attempt_at,id")),
            () -> assertThat(checks.keySet())
                .containsExactlyInAnyOrder("ck_oco_order_id", "ck_oco_attempt_count", "ck_oco_cycle_attempt_count"),
            () -> assertThat(checks.get("ck_oco_order_id")).contains("order_id > 0"),
            () -> assertThat(checks.get("ck_oco_attempt_count")).contains("attempt_count >= 0"),
            () -> assertThat(checks.get("ck_oco_cycle_attempt_count")).contains("cycle_attempt_count >= 0"),
            () -> assertThat(foreignKeys).isZero()
        );
    }

    private static Map<String, Column> columns(JdbcTemplate jdbcTemplate) {
        Map<String, Column> columns = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
            """, rs -> {
            columns.put(rs.getString(1), new Column(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        }, TABLE);
        return columns;
    }

    /** 인덱스 이름 → "unique|index:열1,열2" (SEQ_IN_INDEX 순서). */
    private static Map<String, String> indexes(JdbcTemplate jdbcTemplate) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """, rs -> {
            columns.computeIfAbsent(rs.getString(1), name -> new ArrayList<>()).add(rs.getString(3));
            unique.put(rs.getString(1), rs.getInt(2) == 0);
        }, TABLE);
        Map<String, String> result = new LinkedHashMap<>();
        columns.forEach((name, cols) -> result.put(name, (unique.get(name) ? "unique:" : "index:") + String.join(",", cols)));
        return result;
    }

    private record Column(String columnType, String nullable, String characterSet, String collation) {
    }
}
