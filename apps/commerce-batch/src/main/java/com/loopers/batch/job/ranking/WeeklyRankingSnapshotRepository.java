package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.freeze.PurchaseInput;
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

    /** FROZEN → COMPLETED. 다른 상태면 바꾸지 않고 0 을 돌려준다 */
    public int markCompleted(String snapshotId, LocalDateTime nowUtc) {
        return jdbc.update("""
                update weekly_ranking_snapshot set status = 'COMPLETED', updated_at = ?
                where snapshot_id = ? and status = 'FROZEN'""",
            nowUtc, snapshotId);
    }

    public int countScores(String snapshotId) {
        return jdbc.queryForObject("select count(*) from weekly_ranking_score where snapshot_id = ?", Integer.class, snapshotId);
    }

    public int countInputProducts(String snapshotId) {
        return jdbc.queryForObject(
            "select count(distinct product_id) from weekly_ranking_input where snapshot_id = ?", Integer.class, snapshotId);
    }

    /** 그 기간의 최대 revision snapshot. 실행 진입점이 다음 revision 을 정할 때 이것 하나만 본다 (D-184) */
    public Optional<SnapshotManifest> findLatest(LocalDate periodStart) {
        return jdbc.query("""
                select snapshot_id, period_start, revision, status, input_count, input_checksum
                from weekly_ranking_snapshot where period_start = ? order by revision desc limit 1""",
            (rs, rowNum) -> new SnapshotManifest(
                rs.getString("snapshot_id"),
                rs.getObject("period_start", LocalDate.class),
                rs.getInt("revision"),
                rs.getString("status"),
                rs.getInt("input_count"),
                rs.getString("input_checksum")
            ),
            periodStart).stream().findFirst();
    }

    /** FROZEN · COMPLETED → ABANDONED. 공개본은 포기할 수 없다 (D-185) */
    public int abandon(String snapshotId, LocalDateTime nowUtc) {
        return jdbc.update("""
                update weekly_ranking_snapshot set status = 'ABANDONED', updated_at = ?
                where snapshot_id = ? and status in ('FROZEN', 'COMPLETED')""",
            nowUtc, snapshotId);
    }

    /** 그 기간 원본에 있지만 이 snapshot 입력에 없는 (orderId, productId) 수. 시각으로 판단하지 않는다 (D-179, D-191) */
    public int countMissingFromSnapshot(String snapshotId, LocalDate start, LocalDate end) {
        return jdbc.queryForObject("""
                select count(*)
                from processed_order_confirmation c
                join product_purchase_activity a on a.order_id = c.order_id
                where c.confirmed_at >= ? and c.confirmed_at < ?
                  and not exists (
                      select 1 from weekly_ranking_input i
                      where i.snapshot_id = ? and i.order_id = a.order_id and i.product_id = a.product_id
                  )""",
            Integer.class, start.atStartOfDay(), end.atStartOfDay(), snapshotId);
    }

    public Optional<Integer> findPublishedRevision(LocalDate periodStart) {
        return jdbc.queryForList(
            "select revision from weekly_ranking_snapshot where period_start = ? and status = 'PUBLISHED'",
            Integer.class, periodStart).stream().findFirst();
    }

    /** 점수 내림차순, 동점이면 productId 오름차순으로 1 부터 매긴다. 같은 점수도 순위가 다르다 (과제 계약) */
    public int assignRanks(String snapshotId, LocalDateTime nowUtc) {
        return jdbc.update("""
                update weekly_ranking_score s
                join (
                    select id, row_number() over (order by score desc, product_id asc) as ranked
                    from weekly_ranking_score where snapshot_id = ?
                ) r on r.id = s.id
                set s.ranking = r.ranked, s.updated_at = ?""",
            snapshotId, nowUtc);
    }

    /** 그 기간의 공개본이 이 revision 보다 오래됐을 때만 COMPLETED 로 내린다 (D-180) */
    public int demotePublished(LocalDate periodStart, int revisionBelow, LocalDateTime nowUtc) {
        return jdbc.update("""
                update weekly_ranking_snapshot set status = 'COMPLETED', updated_at = ?
                where period_start = ? and status = 'PUBLISHED' and revision < ?""",
            nowUtc, periodStart, revisionBelow);
    }

    /** COMPLETED → PUBLISHED. 같은 기간에 공개본이 남아 있으면 published_period 유일 제약으로 실패한다 */
    public int promote(String snapshotId, LocalDateTime nowUtc) {
        return jdbc.update("""
                update weekly_ranking_snapshot set status = 'PUBLISHED', updated_at = ?
                where snapshot_id = ? and status = 'COMPLETED'""",
            nowUtc, snapshotId);
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

        public boolean is(String expectedStatus) {
            return status.equals(expectedStatus);
        }
    }
}
