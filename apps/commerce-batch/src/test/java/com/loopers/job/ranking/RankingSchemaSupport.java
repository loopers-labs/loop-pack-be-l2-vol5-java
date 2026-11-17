package com.loopers.job.ranking;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비매핑 테이블은 없을 때만 SQL 로 만들고, 있으면 정의를 확인해 재사용한다. 자동 DROP/ALTER 는 하지 않는다.
 */
final class RankingSchemaSupport {

    static final String SOURCE_TABLE = "product_purchase_activity";
    static final String PROBE_TABLE = "batch_restart_probe";

    /** 열: "이름 타입 NULL여부 ascii collation", 인덱스: "이름 [unique] 열,열". */
    record TableSpec(String name, Set<String> columns, Set<String> indexes, Set<String> checks) {}

    private static final List<String> BASE = List.of(
        "id bigint NO - auto_increment", "created_at datetime(6) NO -", "updated_at datetime(6) NO -", "deleted_at datetime(6) YES -");
    private static final String SNAPSHOT_ID = "snapshot_id varchar(64) NO ascii_bin";

    static final List<TableSpec> RANKING_TABLES = List.of(
        table("ranking_snapshot",
            List.of(SNAPSHOT_ID, "period_start date NO -", "period_end date NO -", "revision bigint NO -",
                "status varchar(16) NO -", "input_fixed_at datetime(6) NO -", "input_count bigint NO -",
                "product_count bigint NO -", "input_checksum char(64) NO ascii_bin",
                "result_checksum char(64) YES ascii_bin", "completed_at datetime(6) YES -"),
            "uk_rs_snapshot unique snapshot_id", "uk_rs_period_revision unique period_start,revision"),
        table("ranking_snapshot_input",
            List.of(SNAPSHOT_ID, "order_id bigint NO -", "product_id bigint NO -", "quantity bigint NO -",
                "confirmed_at datetime(6) NO -"),
            "uk_rsi_snapshot_order_product unique snapshot_id,order_id,product_id",
            "idx_rsi_snapshot_product snapshot_id,product_id"),
        table("ranking_score_stage",
            List.of(SNAPSHOT_ID, "product_id bigint NO -", "score decimal(65,0) NO -"),
            "uk_rss_snapshot_product unique snapshot_id,product_id"),
        table("ranking_result",
            List.of(SNAPSHOT_ID, "product_id bigint NO -", "score decimal(65,0) NO -", "calculated_rank bigint NO -"),
            "uk_rr_snapshot_product unique snapshot_id,product_id", "uk_rr_snapshot_rank unique snapshot_id,calculated_rank"),
        table("ranking_publication",
            List.of("period_start date NO -", "published_snapshot_id varchar(64) NO ascii_bin",
                "published_at datetime(6) NO -"),
            "uk_rp_period unique period_start")
    );

    static final TableSpec SOURCE = new TableSpec(SOURCE_TABLE,
        concat(BASE, List.of("order_id bigint NO -", "product_id bigint NO -", "quantity bigint NO -",
            "confirmed_at datetime(6) NO -")),
        Set.of("PRIMARY unique id", "uk_ppa_order_product unique order_id,product_id", "idx_ppa_confirmed_at confirmed_at"),
        Set.of("ck_ppa_order_id", "ck_ppa_product_id", "ck_ppa_quantity"));

    static final TableSpec PROBE = new TableSpec(PROBE_TABLE,
        Set.of("snapshot_id varchar(64) NO -", "product_id bigint NO -"),
        Set.of("PRIMARY unique snapshot_id,product_id"),
        Set.of());

    private RankingSchemaSupport() {}

    /** 테이블이 없을 때만 SQL 을 실행하고, 만들었으면 true 를 반환한다. */
    static boolean createIfAbsent(DataSource dataSource, String table, String resource) {
        if (tableExists(new JdbcTemplate(dataSource), table)) {
            return false;
        }
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(dataSource);
        return true;
    }

    static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", Integer.class, table) > 0;
    }

    static void assertMatches(JdbcTemplate jdbc, TableSpec spec) {
        String table = spec.name();
        assertThat(jdbc.queryForList("SELECT ENGINE FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table))
            .as(table + " engine").containsExactly("InnoDB");
        assertThat(jdbc.queryForList("SELECT CONCAT_WS(' ', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, "
            + "IF(CHARACTER_SET_NAME = 'ascii', COLLATION_NAME, '-'), NULLIF(EXTRA, '')) "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table))
            .as(table + " columns").containsExactlyInAnyOrderElementsOf(spec.columns());
        assertThat(jdbc.queryForList("SELECT CONCAT_WS(' ', INDEX_NAME, IF(NON_UNIQUE = 0, 'unique', NULL), "
            + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? GROUP BY INDEX_NAME, NON_UNIQUE", String.class, table))
            .as(table + " indexes").containsExactlyInAnyOrderElementsOf(spec.indexes());
        // CHECK 는 기대 목록과 같아야 하고, 물리 FK 는 없어야 한다.
        assertThat(jdbc.queryForList("SELECT CONCAT_WS(' ', CONSTRAINT_TYPE, CONSTRAINT_NAME) "
            + "FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
            + "AND CONSTRAINT_TYPE IN ('CHECK', 'FOREIGN KEY')", String.class, table))
            .as(table + " constraints")
            .containsExactlyInAnyOrderElementsOf(spec.checks().stream().map(name -> "CHECK " + name).toList());
    }

    private static TableSpec table(String name, List<String> columns, String... businessIndexes) {
        return new TableSpec(name, concat(BASE, columns),
            concat(List.of("PRIMARY unique id"), List.of(businessIndexes)), Set.of());
    }

    private static Set<String> concat(List<String> first, List<String> second) {
        return Set.copyOf(Stream.concat(first.stream(), second.stream()).toList());
    }
}
