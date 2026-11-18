package com.loopers.batch.job.ranking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    // 절대값으로 덮는다(+= 아님). 같은 chunk를 다시 써도 값이 같다.
    public void upsertScores(List<ProductScore> scores) {
        jdbcTemplate.batchUpdate("insert into ranking_score_stage(snapshot_id, product_id, score) values (?, ?, ?)"
            + " on duplicate key update score = values(score)", scores, scores.size(), (statement, score) -> {
                statement.setString(1, score.snapshotId());
                statement.setLong(2, score.productId());
                statement.setLong(3, score.score());
            });
    }

    public long distinctInputProducts(String snapshotId) {
        return jdbcTemplate.queryForObject("select count(distinct product_id) from ranking_snapshot_input where snapshot_id = ?",
            Long.class, snapshotId);
    }

    public long stageCount(String snapshotId) {
        return jdbcTemplate.queryForObject("select count(*) from ranking_score_stage where snapshot_id = ?", Long.class,
            snapshotId);
    }

    // "몇 위까지 채웠나"는 새 칸 없이 최대 calc_rank로 판단한다(D156). 없으면 0.
    public int maxCalcRank(String snapshotId) {
        return jdbcTemplate.queryForObject("select coalesce(max(calc_rank), 0) from ranking_result where snapshot_id = ?",
            Integer.class, snapshotId);
    }

    // stage를 score DESC, product_id ASC로 정렬한 계산 순위 중 offset 다음부터 limit개. 순위는 offset + 1부터 매긴다.
    public List<RankedScore> stageRanking(String snapshotId, int offset, int limit) {
        List<RankedScore> ranking = new ArrayList<RankedScore>();
        jdbcTemplate.query("select product_id, score from ranking_score_stage where snapshot_id = ?"
            + " order by score desc, product_id asc limit ? offset ?", resultSet -> {
                ranking.add(new RankedScore(resultSet.getLong(1), resultSet.getLong(2), offset + ranking.size() + 1));
            }, snapshotId, limit, offset);
        return ranking;
    }

    public void insertResults(String snapshotId, List<RankedScore> ranking) {
        jdbcTemplate.batchUpdate("insert into ranking_result(snapshot_id, calc_rank, product_id, score) values (?, ?, ?, ?)",
            ranking, ranking.size(), (statement, row) -> {
                statement.setString(1, snapshotId);
                statement.setInt(2, row.calcRank());
                statement.setLong(3, row.productId());
                statement.setLong(4, row.score());
            });
    }

    // pointer가 없으면 만들고, 있으면 지금 공개된 snapshot의 revision보다 이 snapshot의 revision이 클 때만 바꾼다(D159).
    // revision은 pointer에 두지 않고 snapshot 정보와 조인해 비교한다. 바꾸거나 만들었으면 true다.
    public boolean publish(LocalDate periodStart, String snapshotId) {
        int updated = jdbcTemplate.update("update ranking_publication publication"
            + " join ranking_snapshot current_snapshot on current_snapshot.snapshot_id = publication.snapshot_id"
            + " join ranking_snapshot next_snapshot on next_snapshot.snapshot_id = ?"
            + " set publication.snapshot_id = next_snapshot.snapshot_id"
            + " where publication.period_start = ? and current_snapshot.revision < next_snapshot.revision",
            snapshotId, periodStart);
        if (updated > 0) {
            return true;
        }
        return jdbcTemplate.update("insert into ranking_publication(period_start, snapshot_id) select ?, ? from dual"
            + " where not exists (select 1 from ranking_publication where period_start = ?)",
            periodStart, snapshotId, periodStart) > 0;
    }

    public Optional<String> publishedSnapshotId(LocalDate periodStart) {
        return jdbcTemplate.queryForList("select snapshot_id from ranking_publication where period_start = ?", String.class,
            periodStart).stream().findFirst();
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return resultSet.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
