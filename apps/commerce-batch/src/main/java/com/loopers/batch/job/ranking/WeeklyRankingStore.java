package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 테이블(TB-19 ~ TB-23)과 원본 구매 활동(TB-13)의 SQL. 트랜잭션은 열지 않는다: 호출한 step 의 chunk·tasklet TX 에 참여한다
 * (Boot 의 JdbcTemplate 은 primary DataSource 를 쓰고, JpaTransactionManager 가 같은 DataSource 커넥션을 묶는다).
 * 시각은 UTC 값의 LocalDateTime 으로 주고받는다. JVM 기본 시간대(Asia/Seoul)를 거치지 않는다 (DR-70).
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingStore {
    private final JdbcTemplate jdbc;

    public record Snapshot(
        String snapshotId, LocalDate periodStart, LocalDate periodEnd, long revision, SnapshotStatus status,
        LocalDateTime frozenAt, int inputCount, int productCount, String inputChecksum, String resultChecksum
    ) {
    }

    public record Publication(LocalDate periodStart, String snapshotId, long revision) {
    }

    private static final String SNAPSHOT_COLUMNS = "snapshot_id, period_start, period_end, revision, status, frozen_at, "
        + "input_count, product_count, input_checksum, result_checksum";

    // --- TB-19 snapshot ---

    public Optional<Snapshot> findSnapshotForUpdate(String snapshotId) {
        return jdbc.query("select " + SNAPSHOT_COLUMNS + " from ranking_snapshot where snapshot_id = ? for update",
            WeeklyRankingStore::snapshot, snapshotId).stream().findFirst();
    }

    /** 기간의 가장 큰 revision. 없는 행도 잠가(gap) 같은 기간의 새 snapshot 생성이 겹치지 않게 한다. */
    public Optional<Snapshot> findLatestOfPeriodForUpdate(LocalDate periodStart) {
        return jdbc.query("select " + SNAPSHOT_COLUMNS + " from ranking_snapshot where period_start = ? "
                + "order by revision desc limit 1 for update",
            WeeklyRankingStore::snapshot, periodStart).stream().findFirst();
    }

    public void insertSnapshot(Snapshot snapshot, long jobExecutionId) {
        jdbc.update("insert into ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at, "
                + "input_count, product_count, input_checksum, frozen_by_execution_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            snapshot.snapshotId(), snapshot.periodStart(), snapshot.periodEnd(), snapshot.revision(), snapshot.status().name(),
            snapshot.frozenAt(), snapshot.inputCount(), snapshot.productCount(), snapshot.inputChecksum(), jobExecutionId);
    }

    /** FROZEN 에서 한 번만 바꾼다. 바뀐 행이 없으면 0. */
    public int markPublished(String snapshotId, SnapshotStatus status, String resultChecksum, LocalDateTime publishedAt, long jobExecutionId) {
        return jdbc.update("update ranking_snapshot set status = ?, result_checksum = ?, published_at = ?, published_by_execution_id = ? "
                + "where snapshot_id = ? and status = ?",
            status.name(), resultChecksum, publishedAt, jobExecutionId, snapshotId, SnapshotStatus.FROZEN.name());
    }

    // --- TB-13 → TB-20 입력 고정 ---

    /**
     * 기간 [from, to) 의 구매 활동을 snapshot 입력으로 한 문장에 복사한다 (DR-67). REPEATABLE READ 의 INSERT … SELECT 는
     * 원본의 confirmed_at 범위에 공유 next-key lock 을 걸어, 복사가 commit 될 때까지 그 범위의 새 활동 insert 를 기다리게 한다.
     */
    public int copyInputs(String snapshotId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        return jdbc.update("insert into ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at) "
                + "select ?, order_id, product_id, quantity, confirmed_at from product_purchase_activity "
                + "where confirmed_at >= ? and confirmed_at < ? and deleted_at is null",
            snapshotId, fromUtc, toUtc);
    }

    public List<RankingChecksum.InputLine> readInputs(String snapshotId) {
        return jdbc.query("select order_id, product_id, quantity, confirmed_at from ranking_snapshot_input "
                + "where snapshot_id = ? order by order_id, product_id",
            (rs, n) -> new RankingChecksum.InputLine(rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                rs.getObject("confirmed_at", LocalDateTime.class)),
            snapshotId);
    }

    // --- TB-20 → TB-21 점수 ---

    /** 같은 frozen snapshot 의 상품 하나의 quantity 합. 현재 활동 테이블(TB-13)은 읽지 않는다. */
    public long sumQuantity(String snapshotId, long productId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(quantity), 0) from ranking_snapshot_input where snapshot_id = ? and product_id = ?",
            Long.class, snapshotId, productId);
        return sum == null ? 0 : sum;
    }

    /** 절대값 upsert: 같은 상품을 다시 써도 score = total 로 수렴한다 (score += total 이 아니다). */
    public void upsertScores(String snapshotId, List<? extends ProductScore> scores) {
        jdbc.batchUpdate("insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, ?) as new "
                + "on duplicate key update score = new.score",
            scores, scores.size(), (ps, score) -> {
                ps.setString(1, snapshotId);
                ps.setLong(2, score.productId());
                ps.setLong(3, score.score());
            });
    }

    public int countStage(String snapshotId) {
        Integer count = jdbc.queryForObject("select count(*) from ranking_score_stage where snapshot_id = ?", Integer.class, snapshotId);
        return count == null ? 0 : count;
    }

    public long sumStage(String snapshotId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(score), 0) from ranking_score_stage where snapshot_id = ?", Long.class, snapshotId);
        return sum == null ? 0 : sum;
    }

    public long sumInput(String snapshotId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(quantity), 0) from ranking_snapshot_input where snapshot_id = ?", Long.class, snapshotId);
        return sum == null ? 0 : sum;
    }

    // --- TB-21 → TB-22 완성 ranking ---

    /** stage 전체를 score DESC, product_id ASC 순번으로 한 번에 옮긴다. 동점도 서로 다른 순위다. */
    public int insertRanking(String snapshotId) {
        return jdbc.update("insert into weekly_ranking (snapshot_id, product_id, score, rank_no) "
                + "select snapshot_id, product_id, score, row_number() over (order by score desc, product_id asc) "
                + "from ranking_score_stage where snapshot_id = ?",
            snapshotId);
    }

    public List<RankingChecksum.ResultLine> readRanking(String snapshotId) {
        return jdbc.query("select product_id, score, rank_no from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, n) -> new RankingChecksum.ResultLine(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("rank_no")),
            snapshotId);
    }

    // --- TB-23 공개 pointer ---

    public Optional<Publication> findPublicationForUpdate(LocalDate periodStart) {
        return jdbc.query("select period_start, snapshot_id, revision from weekly_ranking_publication where period_start = ? for update",
            (rs, n) -> new Publication(rs.getObject("period_start", LocalDate.class), rs.getString("snapshot_id"), rs.getLong("revision")),
            periodStart).stream().findFirst();
    }

    public void insertPublication(LocalDate periodStart, String snapshotId, long revision, LocalDateTime publishedAt) {
        jdbc.update("insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at) values (?, ?, ?, ?)",
            periodStart, snapshotId, revision, publishedAt);
    }

    /** 조건부 갱신: 지금 공개된 revision 이 더 작을 때만 바꾼다. 오래된 실행이 pointer 를 되돌리지 못한다. */
    public int replacePublicationIfOlder(LocalDate periodStart, String snapshotId, long revision, LocalDateTime publishedAt) {
        return jdbc.update("update weekly_ranking_publication set snapshot_id = ?, revision = ?, published_at = ? "
                + "where period_start = ? and revision < ?",
            snapshotId, revision, publishedAt, periodStart, revision);
    }

    private static Snapshot snapshot(ResultSet rs, int rowNum) throws SQLException {
        return new Snapshot(
            rs.getString("snapshot_id"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getLong("revision"),
            SnapshotStatus.valueOf(rs.getString("status")),
            rs.getObject("frozen_at", LocalDateTime.class),
            rs.getInt("input_count"),
            rs.getInt("product_count"),
            rs.getString("input_checksum"),
            rs.getString("result_checksum"));
    }
}
