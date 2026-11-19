package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * snapshot(manifest) · 입력 테이블을 JDBC 로 다룬다. 스키마는 commerce-api 엔티티가 만든다 (9주차 D-182, D-183).
 * manifest 상태 전이는 이 클래스에 모은다
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingSnapshotRepository {

    private final JdbcTemplate jdbc;

    public Optional<SnapshotManifest> find(String snapshotId) {
        return jdbc.query("""
                select snapshot_id, period_start, revision, status, input_count, input_checksum
                from weekly_ranking_snapshot where snapshot_id = ?""",
            (rs, rowNum) -> new SnapshotManifest(
                rs.getString("snapshot_id"),
                rs.getObject("period_start", LocalDate.class),
                rs.getInt("revision"),
                rs.getString("status"),
                rs.getInt("input_count"),
                rs.getString("input_checksum")
            ),
            snapshotId).stream().findFirst();
    }

    public void insertInputs(String snapshotId, List<PurchaseInput> inputs, LocalDateTime nowUtc) {
        jdbc.batchUpdate("""
                insert into weekly_ranking_input
                    (snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)""",
            inputs, inputs.size(), (ps, input) -> {
                ps.setString(1, snapshotId);
                ps.setLong(2, input.orderId());
                ps.setLong(3, input.productId());
                ps.setInt(4, input.quantity());
                ps.setObject(5, input.confirmedAtUtc());
                ps.setObject(6, nowUtc);
                ps.setObject(7, nowUtc);
            });
    }

    public void insertFrozen(WeeklyRankingParameters params, LocalDateTime frozenAtUtc, int inputCount, String inputChecksum) {
        jdbc.update("""
                insert into weekly_ranking_snapshot
                    (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum, created_at, updated_at)
                values (?, ?, ?, 'FROZEN', ?, ?, ?, ?, ?)""",
            params.snapshotId(), params.periodStart(), params.revision(), frozenAtUtc, inputCount, inputChecksum,
            frozenAtUtc, frozenAtUtc);
    }

    public record SnapshotManifest(
        String snapshotId,
        LocalDate periodStart,
        int revision,
        String status,
        int inputCount,
        String inputChecksum
    ) {
        public boolean sameIdentityAs(WeeklyRankingParameters params) {
            return periodStart.equals(params.periodStart()) && revision == params.revision();
        }
    }
}
