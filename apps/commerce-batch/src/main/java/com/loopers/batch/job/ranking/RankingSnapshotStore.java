package com.loopers.batch.job.ranking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 테이블(modules:ranking의 ranking-schema.sql)과 원본 구매 활동을 SQL로 다룬다.
 * 같은 DataSource의 JdbcTemplate이라 Batch step의 트랜잭션(Boot의 transactionManager)에 함께 참여한다.
 * 시각은 UTC LocalDateTime으로 읽고 써서 JVM 시간대가 끼어들지 않게 한다.
 */
@Component
public class RankingSnapshotStore {

    private final JdbcTemplate jdbc;

    public RankingSnapshotStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ---- snapshot 정보 ----

    /** 같은 snapshot에 대한 동시 고정·공개를 직렬화하려고 행을 잠그고 읽는다. */
    public Optional<RankingSnapshot> findSnapshotForUpdate(String snapshotId) {
        return jdbc.query("""
                select snapshot_id, period_start, revision, status, frozen_at, input_count, product_count, input_checksum, result_checksum
                from ranking_snapshot where snapshot_id = ? for update""",
            this::mapSnapshot, snapshotId).stream().findFirst();
    }

    public Optional<RankingSnapshot> findSnapshot(String snapshotId) {
        return jdbc.query("""
                select snapshot_id, period_start, revision, status, frozen_at, input_count, product_count, input_checksum, result_checksum
                from ranking_snapshot where snapshot_id = ?""",
            this::mapSnapshot, snapshotId).stream().findFirst();
    }

    public int maxRevision(LocalDate periodStart) {
        Integer max = jdbc.queryForObject(
            "select max(revision) from ranking_snapshot where period_start = ?", Integer.class, periodStart);
        return max == null ? 0 : max;
    }

    public void insertFrozenSnapshot(WeeklySnapshotKey key, LocalDateTime frozenAt, int inputCount, int productCount, String inputChecksum) {
        jdbc.update("""
                insert into ranking_snapshot
                  (snapshot_id, period_start, period_end, revision, status, frozen_at, input_count, product_count, input_checksum)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            key.snapshotId(), key.periodStart(), key.periodEnd(), key.revision(), RankingSnapshot.Status.FROZEN.name(),
            frozenAt, inputCount, productCount, inputChecksum);
    }

    // ---- snapshot 입력 ----

    /**
     * 그 주에 확정된 구매 활동을 snapshot 입력으로 복사한다. 구간은 시작 포함·끝 제외다.
     * InnoDB의 INSERT … SELECT는 원본을 잠금 읽기로 읽으므로, 이 문장 시점에 commit된 행 집합이 그대로 복사된다.
     */
    public int copyActivities(WeeklySnapshotKey key) {
        return jdbc.update("""
                insert into ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
                select ?, order_id, product_id, quantity, confirmed_at
                from product_purchase_activity
                where confirmed_at >= ? and confirmed_at < ?""",
            key.snapshotId(), key.startInclusive(), key.endExclusive());
    }

    public int countNonPositiveQuantity(String snapshotId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ranking_snapshot_input where snapshot_id = ? and quantity <= 0", Integer.class, snapshotId);
        return count == null ? 0 : count;
    }

    public int countProducts(String snapshotId) {
        Integer count = jdbc.queryForObject(
            "select count(distinct product_id) from ranking_snapshot_input where snapshot_id = ?", Integer.class, snapshotId);
        return count == null ? 0 : count;
    }

    /** 입력 checksum: (order_id, product_id) 순서로 직렬화한다. 행을 메모리에 모으지 않고 흘려 보낸다. */
    public String inputChecksum(String snapshotId) {
        RankingChecksum checksum = new RankingChecksum();
        jdbc.query("""
                select order_id, product_id, quantity, confirmed_at from ranking_snapshot_input
                where snapshot_id = ? order by order_id, product_id""",
            rs -> {
                checksum.addInput(
                    rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                    rs.getObject("confirmed_at", LocalDateTime.class));
            },
            snapshotId);
        return checksum.hex();
    }

    // ---- 점수 계산 ----

    /** 같은 snapshot 입력에서 한 상품의 수량 합. 현재 활동 테이블은 읽지 않는다. */
    public long sumQuantity(String snapshotId, long productId) {
        Long sum = jdbc.queryForObject(
            "select coalesce(sum(quantity), 0) from ranking_snapshot_input where snapshot_id = ? and product_id = ?",
            Long.class, snapshotId, productId);
        return sum == null ? 0L : sum;
    }

    /** 상품별 절대 점수를 저장한다. 같은 상품을 다시 써도 score = total로 수렴한다. */
    public void upsertScores(String snapshotId, List<ProductScore> scores) {
        jdbc.batchUpdate("""
                insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, ?)
                on duplicate key update score = values(score)""",
            scores, scores.size(), (ps, score) -> {
                ps.setString(1, snapshotId);
                ps.setLong(2, score.productId());
                ps.setLong(3, score.score());
            });
    }

    private RankingSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new RankingSnapshot(
            rs.getString("snapshot_id"),
            rs.getObject("period_start", LocalDate.class),
            rs.getInt("revision"),
            RankingSnapshot.Status.valueOf(rs.getString("status")),
            rs.getObject("frozen_at", LocalDateTime.class),
            rs.getInt("input_count"),
            rs.getInt("product_count"),
            rs.getString("input_checksum"),
            rs.getString("result_checksum")
        );
    }
}
