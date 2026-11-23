package com.loopers.job.ranking.support;

import com.loopers.utils.RepositorySqlScripts;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class WeeklyRankingTestDatabase {
    private static final List<String> OWNED_TABLES = List.of(
            "batch_restart_probe", "ranking_publication", "ranking_result", "ranking_score_stage",
            "ranking_snapshot_input", "ranking_snapshot", "product_purchase_activity", "processed_order_confirmation");
    private static final List<String> BATCH_METADATA_TABLES = List.of(
            "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_CONTEXT",
            "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public WeeklyRankingTestDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void reset() {
        OWNED_TABLES.forEach(table -> jdbc.execute("DROP TABLE IF EXISTS " + table));
        RepositorySqlScripts.run(dataSource,
                "docs/week6/sql/purchase-activity-tables.sql",
                "docs/week9/sql/weekly-ranking-tables.sql");
        BATCH_METADATA_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    public void insertActivity(long orderId, long productId, int quantity, Instant confirmedAt) {
        jdbc.update("INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at) "
                + "VALUES (?, ?, ?, ?)", orderId, productId, quantity, utc(confirmedAt));
    }

    public void insertWeek20260817Fixture() {
        insertActivity(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        insertActivity(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
        insertActivity(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        insertActivity(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
