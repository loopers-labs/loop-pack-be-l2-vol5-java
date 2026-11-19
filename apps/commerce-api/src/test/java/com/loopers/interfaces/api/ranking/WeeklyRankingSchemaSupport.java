package com.loopers.interfaces.api.ranking;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 단독 테스트의 랭킹 다섯 테이블 준비(랭킹 설계 11.3.1). 모두 없으면 SQL 로 만들고, 있으면 정의를 확인해 재사용한다.
 * 일부만 있거나 정의가 다르면 실패한다. 자동 DROP/ALTER 는 하지 않는다.
 * 실제 연결 테스트도 batch 준비 child 가 만든 테이블을 {@link #assertDefinitions} 로 같은 기준에 대조한다.
 */
public final class WeeklyRankingSchemaSupport {

    private static final List<String> BASE = List.of(
        "id bigint NO - auto_increment", "created_at datetime(6) NO -", "updated_at datetime(6) NO -",
        "deleted_at datetime(6) YES -");
    private static final String SNAPSHOT_ID = "snapshot_id varchar(64) NO ascii_bin";

    /** 테이블별 열("이름 타입 NULL여부 ascii collation [extra]")과 인덱스("이름 [unique] 열,열"). */
    private static final Map<String, List<List<String>>> TABLES = Map.of(
        "ranking_snapshot", List.of(
            List.of(SNAPSHOT_ID, "period_start date NO -", "period_end date NO -", "revision bigint NO -",
                "status varchar(16) NO -", "input_fixed_at datetime(6) NO -", "input_count bigint NO -",
                "product_count bigint NO -", "input_checksum char(64) NO ascii_bin",
                "result_checksum char(64) YES ascii_bin", "completed_at datetime(6) YES -"),
            List.of("uk_rs_snapshot unique snapshot_id", "uk_rs_period_revision unique period_start,revision")),
        "ranking_snapshot_input", List.of(
            List.of(SNAPSHOT_ID, "order_id bigint NO -", "product_id bigint NO -", "quantity bigint NO -",
                "confirmed_at datetime(6) NO -"),
            List.of("uk_rsi_snapshot_order_product unique snapshot_id,order_id,product_id",
                "idx_rsi_snapshot_product snapshot_id,product_id")),
        "ranking_score_stage", List.of(
            List.of(SNAPSHOT_ID, "product_id bigint NO -", "score decimal(65,0) NO -"),
            List.of("uk_rss_snapshot_product unique snapshot_id,product_id")),
        "ranking_result", List.of(
            List.of(SNAPSHOT_ID, "product_id bigint NO -", "score decimal(65,0) NO -", "calculated_rank bigint NO -"),
            List.of("uk_rr_snapshot_product unique snapshot_id,product_id",
                "uk_rr_snapshot_rank unique snapshot_id,calculated_rank")),
        "ranking_publication", List.of(
            List.of("period_start date NO -", "published_snapshot_id varchar(64) NO ascii_bin",
                "published_at datetime(6) NO -"),
            List.of("uk_rp_period unique period_start")));

    private WeeklyRankingSchemaSupport() {}

    static void prepare(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long existing = TABLES.keySet().stream().filter(table -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            Integer.class, table) > 0).count();
        assertThat(existing).as("랭킹 테이블이 일부만 있음").isIn(0L, (long) TABLES.size());
        if (existing == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("fixtures/weekly-ranking-schema.sql")).execute(dataSource);
        }
        assertDefinitions(jdbc);
    }

    /** 다섯 랭킹 테이블의 열 타입·null·ASCII collation, 명명 인덱스·열 순서·유일성, CHECK/FK 없음, InnoDB 를 확인한다. */
    public static void assertDefinitions(JdbcTemplate jdbc) {
        TABLES.forEach((table, spec) -> assertMatches(jdbc, table, spec.get(0), spec.get(1)));
    }

    private static void assertMatches(JdbcTemplate jdbc, String table, List<String> columns, List<String> indexes) {
        assertThat(jdbc.queryForList("SELECT ENGINE FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table)).containsExactly("InnoDB");
        assertThat(jdbc.queryForList("SELECT CONCAT_WS(' ', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, "
            + "IF(CHARACTER_SET_NAME = 'ascii', COLLATION_NAME, '-'), NULLIF(EXTRA, '')) "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table))
            .as(table + " columns").containsExactlyInAnyOrderElementsOf(Stream.concat(BASE.stream(), columns.stream()).toList());
        assertThat(jdbc.queryForList("SELECT CONCAT_WS(' ', INDEX_NAME, IF(NON_UNIQUE = 0, 'unique', NULL), "
            + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? GROUP BY INDEX_NAME, NON_UNIQUE", String.class, table))
            .as(table + " indexes")
            .containsExactlyInAnyOrderElementsOf(Stream.concat(Stream.of("PRIMARY unique id"), indexes.stream()).toList());
        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_TYPE IN ('CHECK', 'FOREIGN KEY')",
            String.class, table)).as(table + " CHECK/FK").isEmpty();
    }
}
