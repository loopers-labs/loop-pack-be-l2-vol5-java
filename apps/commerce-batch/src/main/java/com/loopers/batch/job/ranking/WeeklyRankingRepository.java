package com.loopers.batch.job.ranking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

// 주간 랭킹 snapshot의 JDBC 접근. 원본은 streamer 소유 product_purchase_activity를 JDBC로 읽는다(D75 범위 확장).
// 시각은 UTC LocalDateTime으로 주고받는다(원본·snapshot 모두 UTC로 저장). snapshot 입력을 UPDATE·DELETE하는 메서드는 두지 않는다(D148).
public class WeeklyRankingRepository {
    private final JdbcTemplate jdbcTemplate;

    public WeeklyRankingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SnapshotInfo> findInfo(String snapshotId) {
        return jdbcTemplate.query("select snapshot_id, period_start, revision, frozen_at, input_count, input_checksum"
            + " from ranking_snapshot where snapshot_id = ?", (resultSet, rowNumber) -> new SnapshotInfo(resultSet.getString(1),
                resultSet.getObject(2, LocalDate.class), resultSet.getInt(3), instant(resultSet, 4),
                resultSet.getInt(5), resultSet.getString(6)), snapshotId)
            .stream().findFirst();
    }

    // 평범한 SELECT다(FOR UPDATE·INSERT … SELECT 아님). 원본에 잠금을 걸지 않고 step TX의 일관된 읽기로 그 시점의 행만 본다(D149).
    public List<SourcePurchase> readSource(LocalDate periodStart, LocalDate periodEnd) {
        return jdbcTemplate.query("select order_id, product_id, quantity, confirmed_at from product_purchase_activity"
                + " where confirmed_at >= ? and confirmed_at < ? order by order_id, product_id",
            (resultSet, rowNumber) -> new SourcePurchase(resultSet.getLong(1), resultSet.getLong(2), resultSet.getInt(3),
                instant(resultSet, 4)),
            periodStart.atStartOfDay(), periodEnd.atStartOfDay());
    }

    public void insertInputs(String snapshotId, List<SourcePurchase> rows) {
        jdbcTemplate.batchUpdate("insert into ranking_snapshot_input(snapshot_id, order_id, product_id, quantity, confirmed_at)"
            + " values (?, ?, ?, ?, ?)", rows, rows.size(), (statement, row) -> {
                statement.setString(1, snapshotId);
                statement.setLong(2, row.orderId());
                statement.setLong(3, row.productId());
                statement.setInt(4, row.quantity());
                statement.setObject(5, LocalDateTime.ofInstant(row.confirmedAt(), ZoneOffset.UTC));
            });
    }

    public void insertInfo(SnapshotInfo info) {
        jdbcTemplate.update("insert into ranking_snapshot(snapshot_id, period_start, revision, frozen_at, input_count,"
            + " input_checksum) values (?, ?, ?, ?, ?, ?)", info.snapshotId(), info.periodStart(), info.revision(),
            LocalDateTime.ofInstant(info.frozenAt(), ZoneOffset.UTC), info.inputCount(), info.inputChecksum());
    }

    // 원본이 아니라 snapshot 입력에서 합산한다.
    public long scoreOf(String snapshotId, long productId) {
        Long score = jdbcTemplate.queryForObject("select coalesce(sum(quantity), 0) from ranking_snapshot_input"
            + " where snapshot_id = ? and product_id = ?", Long.class, snapshotId, productId);
        return score == null ? 0 : score;
    }

    // 절대값으로 덮는다(+= 아님). 같은 chunk를 다시 써도 값이 같다.
    public void upsertScores(List<ProductScore> scores) {
        jdbcTemplate.batchUpdate("insert into ranking_score_stage(snapshot_id, product_id, score) values (?, ?, ?)"
            + " on duplicate key update score = values(score)", scores, scores.size(), (statement, score) -> {
                statement.setString(1, score.snapshotId());
                statement.setLong(2, score.productId());
                statement.setLong(3, score.score());
            });
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return resultSet.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
