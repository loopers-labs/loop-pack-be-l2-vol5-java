package com.loopers.batch.job.ranking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 SQL. JobRepository와 같은 DataSource를 쓰므로 Step 트랜잭션에 함께 묶인다.
 * 시각은 UTC 날짜·시각(LocalDateTime)으로 넣고 읽는다. JdbcTemplate에는 Hibernate의 UTC 설정이 적용되지 않는다.
 */
@Repository
public class WeeklyRankingRepository {

    private final JdbcTemplate jdbc;
    // MySQL 드라이버는 fetch size가 Integer.MIN_VALUE일 때 결과를 한 줄씩 흘려 보낸다. 순위가 많아도 한꺼번에 메모리에 올리지 않는다.
    private final JdbcTemplate streaming;

    public WeeklyRankingRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(Integer.MIN_VALUE);
    }

    public Optional<WeeklyRankingRevisionStatus> findStatus(LocalDate periodStart, int revision) {
        return jdbc.queryForList(
                "SELECT status FROM weekly_ranking_revisions WHERE period_start = ? AND revision = ?",
                String.class, periodStart, revision)
            .stream()
            .findFirst()
            .map(WeeklyRankingRevisionStatus::valueOf);
    }

    // 닫은 회차도 번호에 포함한다. 다른 실행이 점수를 고정하는 중이면 커밋 전이라 보이지 않는다.
    public int lastRevision(LocalDate periodStart) {
        return jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) FROM weekly_ranking_revisions WHERE period_start = ?",
            Integer.class, periodStart);
    }

    // 다른 실행이 점수 고정을 커밋한 뒤면 그 회차가 여기서 보인다.
    public long countCalculating(LocalDate periodStart) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_revisions WHERE period_start = ? AND status = 'CALCULATING'",
            Long.class, periodStart);
    }

    public long countInvalidActivities(RankingPeriod period) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_purchase_activity
                WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL AND quantity <= 0""",
            Long.class, period.startAtUtc(), period.endAtUtc());
    }

    // 한 문장의 일관된 읽기로 점수를 넣는다. 순위는 비워 둔다. 커밋하면 이 회차의 고정본이다.
    public int insertScores(RankingPeriod period, int revision) {
        return jdbc.update("""
                INSERT INTO weekly_ranking_scores (period_start, revision, product_id, score)
                SELECT ?, ?, product_id, SUM(quantity)
                FROM product_purchase_activity
                WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
                GROUP BY product_id""",
            period.start(), revision, period.startAtUtc(), period.endAtUtc());
    }

    public void insertCalculatingRevision(LocalDate periodStart, int revision, LocalDateTime calculatedAt,
                                          long jobExecutionId) {
        jdbc.update("""
                INSERT INTO weekly_ranking_revisions (period_start, revision, status, calculated_at, job_execution_id)
                VALUES (?, ?, 'CALCULATING', ?, ?)""",
            periodStart, revision, calculatedAt, jobExecutionId);
    }

    // 계산 중인 회차의 순위만 비운다. 완성되거나 닫힌 회차는 바뀌지 않는다.
    // 순위 Step이 시작하기 전, 묶음 트랜잭션 밖에서 한 문장으로 커밋된다.
    public int clearRanks(LocalDate periodStart, int revision) {
        return jdbc.update("""
                UPDATE weekly_ranking_scores s
                JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision
                SET s.rank_no = NULL
                WHERE s.period_start = ? AND s.revision = ? AND r.status = 'CALCULATING' AND s.rank_no IS NOT NULL""",
            periodStart, revision);
    }

    // 계산 중인 회차에만 순위를 절대값으로 쓰고, 바뀐 행 수를 돌려준다.
    public int writeRanks(LocalDate periodStart, int revision, List<? extends ProductRank> ranks) {
        int[] counts = jdbc.batchUpdate("""
                UPDATE weekly_ranking_scores s
                JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision
                SET s.rank_no = ?
                WHERE s.period_start = ? AND s.revision = ? AND s.product_id = ? AND r.status = 'CALCULATING'""",
            ranks.stream().map(rank -> new Object[]{rank.rank(), periodStart, revision, rank.productId()}).toList());
        return Arrays.stream(counts).sum();
    }

    public RankStats rankStats(LocalDate periodStart, int revision) {
        return jdbc.queryForObject("""
                SELECT COUNT(*), COUNT(rank_no), MIN(rank_no), MAX(rank_no)
                FROM weekly_ranking_scores WHERE period_start = ? AND revision = ?""",
            (result, rowNumber) -> new RankStats(result.getLong(1), result.getLong(2),
                result.getObject(3, Integer.class), result.getObject(4, Integer.class)),
            periodStart, revision);
    }

    public String resultChecksum(LocalDate periodStart, int revision) {
        RankingChecksum checksum = new RankingChecksum();
        streaming.query("""
                SELECT product_id, score, rank_no FROM weekly_ranking_scores
                WHERE period_start = ? AND revision = ? ORDER BY rank_no""",
            (RowCallbackHandler) result -> checksum.add(
                result.getLong("product_id"), result.getLong("score"), result.getInt("rank_no")),
            periodStart, revision);
        return checksum.hex();
    }

    // 계산 중인 회차만 완성한다. 그사이 운영자가 닫았으면 바뀌는 행이 없다.
    public int complete(LocalDate periodStart, int revision, String resultChecksum) {
        return jdbc.update("""
                UPDATE weekly_ranking_revisions SET status = 'COMPLETED', result_checksum = ?
                WHERE period_start = ? AND revision = ? AND status = 'CALCULATING'""",
            resultChecksum, periodStart, revision);
    }
}
