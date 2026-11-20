package com.loopers.interfaces.api.ranking;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * weekly_ranking_* 네 테이블은 공유 모듈 ranking-schema 의 엔티티를 api 의 ddl-auto 가 만든다(설계 D14). 이 헬퍼는 행 삽입·기간별 삭제만 맡는다.
 * api 는 이 테이블에 Repository 를 두지 않고 JDBC 로 읽기만 하므로(R5) 테스트도 JDBC 로 넣는다.
 */
final class WeeklyRankingTableSupport {

    private static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;

    WeeklyRankingTableSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
