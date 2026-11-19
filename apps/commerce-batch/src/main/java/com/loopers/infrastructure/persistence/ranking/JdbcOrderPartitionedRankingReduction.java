package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOrderPartitionedRankingReduction {
    private final JdbcTemplate jdbc;

    public JdbcOrderPartitionedRankingReduction(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    public record Plan(LocalDate periodStart, int revision, int orderPartitionCount, String state, int contractVersion,
                       LocalDateTime frozenAt, InputFingerprint inputs) { }

    private record Manifest(int orderPartitionId, Long lower, Long upper, boolean empty, String state, LocalDateTime frozenAt,
                            InputFingerprint inputs) { }

    public Plan lockPlan(UUID id) {
        return jdbc.queryForObject("""
            SELECT p.period_start,p.revision,p.order_partition_count,p.state,m.input_contract_version,m.frozen_at,m.input_count,m.input_checksum
            FROM weekly_ranking_plans p JOIN weekly_ranking_snapshots m USING(snapshot_id)
            WHERE p.snapshot_id=? FOR UPDATE
            """, (row, index) -> new Plan(row.getObject(1, LocalDate.class), row.getInt(2), row.getInt(3), row.getString(4),
            row.getInt(5), row.getObject(6, LocalDateTime.class),
            row.getObject(6) == null ? null : new InputFingerprint(row.getLong(7), row.getString(8))), id.toString());
    }

    private boolean hasConflicts(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM weekly_ranking_score_conflicts WHERE snapshot_id=?)
            """, Boolean.class, id.toString()));
    }

    public void finish(UUID id, String state) {
        int changed = jdbc.update("UPDATE weekly_ranking_plans SET state=? WHERE snapshot_id=? AND state='OPEN'", state, id.toString());
        if (changed == 1 && "CONFLICT".equals(state)) {
            WeeklyPlanQuarantine.blockOpenSiblings(jdbc, id);
        }
    }

    public Optional<InputFingerprint> verifyFrozenInputs(UUID id, Plan plan) {
        var period = new RankingPeriod(plan.periodStart());
        var manifests = jdbc.query("""
            SELECT order_partition_id,order_min,order_max,is_empty,state,frozen_at,input_count,input_checksum
            FROM weekly_ranking_order_partitions WHERE snapshot_id=? ORDER BY order_partition_id FOR UPDATE
            """, (row, index) -> new Manifest(row.getInt(1), row.getObject(2, Long.class), row.getObject(3, Long.class),
            row.getBoolean(4), row.getString(5), row.getObject(6, LocalDateTime.class),
            row.getObject(6) == null ? null : new InputFingerprint(row.getLong(7), row.getString(8))), id.toString());
        if (hasConflicts(id)) {
            finish(id, "CONFLICT");
            return Optional.empty();
        }
        if (manifests.size() != plan.orderPartitionCount()) {
            throw new SnapshotIntegrityException("확정된 주문 파티션 수가 주간 계획과 다릅니다.");
        }
        if (manifests.stream().anyMatch(orderPartition -> !"DONE".equals(orderPartition.state()))) {
            return Optional.empty();
        }
        var nonEmptyOrderPartitions = manifests.stream().filter(orderPartition -> !orderPartition.empty()).toList();
        requireFullCoverage(nonEmptyOrderPartitions);
        var cuts = nonEmptyOrderPartitions.stream().map(Manifest::upper).filter(Objects::nonNull).toList();
        var observed = WeeklyInputFingerprints.read(jdbc, id, period, cuts, manifests.size());
        if (plan.frozenAt() == null || !observed.week().equals(plan.inputs())) {
            throw new SnapshotIntegrityException("계획 시점의 주간 입력 건수 또는 checksum이 다릅니다: " + id);
        }
        for (int index = 0; index < manifests.size(); index++) {
            var partition = manifests.get(index);
            if (partition.orderPartitionId() != index || partition.empty() != (index >= nonEmptyOrderPartitions.size())
                || !plan.frozenAt().equals(partition.frozenAt()) || !observed.partitions().get(index).equals(partition.inputs())) {
                throw new SnapshotIntegrityException("주간 고정 시각 또는 주문 파티션의 입력 건수·checksum이 다릅니다: " + index);
            }
        }
        return Optional.of(observed.week());
    }

    private static void requireFullCoverage(List<Manifest> nonEmptyOrderPartitions) {
        if (nonEmptyOrderPartitions.isEmpty() || nonEmptyOrderPartitions.getFirst().lower() != null || nonEmptyOrderPartitions.getLast().upper() != null) {
            throw new SnapshotIntegrityException("주문 파티션의 양끝이 열려 있어야 합니다.");
        }
        for (int index = 1; index < nonEmptyOrderPartitions.size(); index++) {
            Long lower = nonEmptyOrderPartitions.get(index).lower();
            if (lower == null || !Objects.equals(nonEmptyOrderPartitions.get(index - 1).upper(), lower)) {
                throw new SnapshotIntegrityException("주문 파티션이 겹치거나 비었습니다.");
            }
        }
    }

    private void requireMatchingPartials(UUID id) {
        // A weekly total can be correct even when two order partitions have opposite errors.
        boolean mismatched = Boolean.TRUE.equals(jdbc.queryForObject("""
            WITH contributions AS (
                SELECT s.order_partition_id,i.product_id,SUM(i.quantity) AS difference
                FROM weekly_ranking_order_partitions s JOIN weekly_ranking_snapshot_inputs i
                  ON i.snapshot_id=s.snapshot_id AND (s.order_min IS NULL OR i.order_id>=s.order_min)
                  AND (s.order_max IS NULL OR i.order_id<s.order_max)
                WHERE s.snapshot_id=? AND NOT s.is_empty GROUP BY s.order_partition_id,i.product_id
                UNION ALL
                SELECT order_partition_id,product_id,-CAST(score AS DECIMAL(65,0)) FROM weekly_ranking_partial_scores WHERE snapshot_id=?
            )
            SELECT EXISTS(SELECT 1 FROM contributions GROUP BY order_partition_id,product_id HAVING SUM(difference)<>0)
            """, Boolean.class, id.toString(), id.toString()));
        if (mismatched) {
            throw new SnapshotIntegrityException("주문 파티션별 부분 점수와 고정 입력의 상품별 수량 합이 다릅니다: " + id);
        }
    }

    public void reduce(UUID id) {
        requireMatchingPartials(id);
        boolean invalidStage = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM weekly_ranking_score_stage WHERE snapshot_id=?)
              OR EXISTS(SELECT 1 FROM weekly_ranking_partial_scores WHERE snapshot_id=?
                        GROUP BY product_id HAVING SUM(score)>9223372036854775807)
            """, Boolean.class, id.toString(), id.toString()));
        if (invalidStage) {
            throw new SnapshotIntegrityException("미공개 실행에 최종 점수가 있거나 상품별 합계가 BIGINT 범위를 넘었습니다: " + id);
        }
        jdbc.update("""
            INSERT INTO weekly_ranking_score_stage(snapshot_id,product_id,score)
            SELECT snapshot_id,product_id,SUM(score) FROM weekly_ranking_partial_scores
            WHERE snapshot_id=? GROUP BY snapshot_id,product_id
            """, id.toString());
    }
}
