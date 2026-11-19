package com.loopers.interfaces.api.ranking;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * api 컨텍스트에는 weekly_ranking_* 엔티티가 없으므로(설계 D14: 테이블 정의는 batch 소유) 테스트가 DDL 을 직접 실행한다.
 * 네 DDL 은 batch 엔티티가 ddl-auto 로 만드는 것과 1:1 (설계 3절). batch 엔티티가 바뀌면 여기도 따라 고쳐야 한다.
 */
final class WeeklyRankingTableSupport {

    static final String[] DDL = {
        """
        CREATE TABLE IF NOT EXISTS weekly_ranking_inputs (
            id BIGINT NOT NULL AUTO_INCREMENT, snapshot_id VARCHAR(64) NOT NULL, order_id BIGINT NOT NULL,
            product_id BIGINT NOT NULL, quantity INT NOT NULL, confirmed_at DATETIME(6) NOT NULL,
            created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6) NULL,
            PRIMARY KEY (id), UNIQUE KEY uk_wri_snapshot_order_product (snapshot_id, order_id, product_id)
        ) ENGINE=InnoDB
        """,
        """
        CREATE TABLE IF NOT EXISTS weekly_ranking_revisions (
            snapshot_id VARCHAR(64) NOT NULL, period_start DATE NOT NULL, revision INT NOT NULL, status VARCHAR(20) NOT NULL,
            frozen_at DATETIME(6) NOT NULL, input_count INT NOT NULL, expected_product_count INT NOT NULL,
            input_checksum CHAR(64) NOT NULL, result_checksum CHAR(64) NULL,
            created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
            PRIMARY KEY (snapshot_id), UNIQUE KEY uk_wrr_period_revision (period_start, revision)
        ) ENGINE=InnoDB
        """,
        """
        CREATE TABLE IF NOT EXISTS weekly_ranking_scores (
            id BIGINT NOT NULL AUTO_INCREMENT, snapshot_id VARCHAR(64) NOT NULL, product_id BIGINT NOT NULL,
            score BIGINT NOT NULL, ranking INT NULL,
            created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6) NULL,
            PRIMARY KEY (id), UNIQUE KEY uk_wrs_snapshot_product (snapshot_id, product_id),
            KEY idx_wrs_snapshot_ranking (snapshot_id, ranking)
        ) ENGINE=InnoDB
        """,
        """
        CREATE TABLE IF NOT EXISTS weekly_ranking_publications (
            period_start DATE NOT NULL, snapshot_id VARCHAR(64) NOT NULL, revision INT NOT NULL,
            published_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
            PRIMARY KEY (period_start)
        ) ENGINE=InnoDB
        """
    };

    private static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;

    WeeklyRankingTableSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void createTables() {
        for (String ddl : DDL) {
            jdbcTemplate.execute(ddl);
        }
    }

    /** DatabaseCleanUp 은 이 네 테이블을 모르므로 테스트 기간의 행만 직접 지운다. */
    void deletePeriod(LocalDate periodStart) {
        jdbcTemplate.update("DELETE FROM weekly_ranking_publications WHERE period_start = ?", periodStart);
        jdbcTemplate.update("DELETE s FROM weekly_ranking_scores s JOIN weekly_ranking_revisions r ON r.snapshot_id = s.snapshot_id "
            + "WHERE r.period_start = ?", periodStart);
        jdbcTemplate.update("DELETE i FROM weekly_ranking_inputs i JOIN weekly_ranking_revisions r ON r.snapshot_id = i.snapshot_id "
            + "WHERE r.period_start = ?", periodStart);
        jdbcTemplate.update("DELETE FROM weekly_ranking_revisions WHERE period_start = ?", periodStart);
    }

    void insertRevision(String snapshotId, LocalDate periodStart, int revision, String status, LocalDateTime frozenAtUtc, int productCount) {
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_revisions (snapshot_id, period_start, revision, status, frozen_at, input_count, "
                + "expected_product_count, input_checksum, result_checksum, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            snapshotId, periodStart, revision, status, frozenAtUtc, productCount, productCount, EMPTY_CHECKSUM,
            "COMPLETED".equals(status) ? EMPTY_CHECKSUM : null, frozenAtUtc, frozenAtUtc);
    }

    void insertScore(String snapshotId, long productId, long score, Integer ranking, LocalDateTime nowUtc) {
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_scores (snapshot_id, product_id, score, ranking, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            snapshotId, productId, score, ranking, nowUtc, nowUtc);
    }

    void insertPublication(LocalDate periodStart, String snapshotId, int revision, LocalDateTime publishedAtUtc) {
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_publications (period_start, snapshot_id, revision, published_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            periodStart, snapshotId, revision, publishedAtUtc, publishedAtUtc);
    }
}
