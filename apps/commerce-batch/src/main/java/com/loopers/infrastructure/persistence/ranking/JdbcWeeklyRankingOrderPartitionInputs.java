package com.loopers.infrastructure.persistence.ranking;

import com.loopers.application.ranking.command.WeeklyRankingOrderPartitionCommand;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWeeklyRankingOrderPartitionInputs {
    private final NamedParameterJdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate streaming;

    public JdbcWeeklyRankingOrderPartitionInputs(DataSource source) {
        jdbc = new NamedParameterJdbcTemplate(source);
        var cursor = new JdbcTemplate(source);
        cursor.setFetchSize(Integer.MIN_VALUE);
        streaming = new NamedParameterJdbcTemplate(cursor);
    }

    @Transactional
    public void bindExecution(WeeklyRankingOrderPartitionCommand command, long executionId) {
        var params = parameters(command).addValue("execution", executionId);
        int matched = jdbc.update("""
            UPDATE weekly_ranking_order_partitions s JOIN weekly_ranking_plans p ON p.snapshot_id=s.snapshot_id
            SET s.execution_id=:execution
            WHERE s.snapshot_id=:snapshot AND s.order_partition_id=:orderPartition AND s.state='RUNNING'
              AND s.owner_id=:owner AND s.generation=:generation AND p.state='OPEN'
              AND p.period_start=:period AND p.revision=:revision
              AND (s.execution_id IS NULL OR s.execution_id=:execution)
            """, params);
        if (matched != 1) {
            throw new SnapshotConflictException("주문 파티션 실행의 현재 소유권 또는 식별값이 다릅니다.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void freeze(WeeklyRankingOrderPartitionCommand command) {
        var params = parameters(command);
        var orderPartition = load(params, true, "RUNNING");
        if (orderPartition.fingerprint() != null) {
            verify(command, orderPartition, params);
            return;
        }
        params.addValue("start", LocalDateTime.ofInstant(command.period().startInclusive(), ZoneOffset.UTC));
        params.addValue("end", LocalDateTime.ofInstant(command.period().endExclusive(), ZoneOffset.UTC));
        String bounds = orderPartition.bounds().predicate(params);
        if (!orderPartition.empty()) {
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshot_inputs(snapshot_id,order_id,product_id,quantity,confirmed_at)
                SELECT :snapshot,order_id,product_id,quantity,confirmed_at FROM product_purchase_activities
                WHERE confirmed_at>=:start AND confirmed_at<:end
                """ + bounds + " ORDER BY order_id,product_id", params);
        }
        var input = fingerprint(command, orderPartition, params);
        jdbc.update("""
            UPDATE weekly_ranking_order_partitions SET frozen_at=UTC_TIMESTAMP(6),input_count=:count,input_checksum=:checksum
            WHERE snapshot_id=:snapshot AND order_partition_id=:orderPartition
            """, params.addValue("count", input.count()).addValue("checksum", input.sha256()));
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<LocalProductPartition> localProductPartitions(WeeklyRankingOrderPartitionCommand command) {
        var params = parameters(command);
        var orderPartition = load(params, false, "RUNNING");
        verify(command, orderPartition, params);
        if (orderPartition.empty() || orderPartition.fingerprint().count() == 0) {
            return List.of();
        }
        // The grid and frozen rows belong to this plan and survive a node or pool-size change.
        String bounds = orderPartition.bounds().predicate(params);
        return jdbc.query("""
            SELECT tile,MIN(product_id) AS first_product,MAX(product_id) AS last_product FROM (
                SELECT product_id,NTILE(:grid) OVER (ORDER BY product_id) AS tile FROM (
                    SELECT DISTINCT product_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=:snapshot
            """ + bounds + ") products) tiles GROUP BY tile ORDER BY tile", params.addValue("grid", orderPartition.grid()),
            (row, index) -> new LocalProductPartition(row.getInt("tile") - 1, row.getLong("first_product"),
                row.getLong("last_product"), orderPartition.bounds()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WeeklyPurchaseScore calculate(UUID snapshotId, long productId, OrderPartitionBounds bounds) {
        var params = new MapSqlParameterSource("snapshot", snapshotId.toString()).addValue("product", productId);
        return jdbc.queryForObject("""
            SELECT SUM(quantity) AS total,COUNT(*) AS input_count,MIN(quantity) AS minimum
            FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=:snapshot AND product_id=:product
            """ + bounds.predicate(params), params, (row, index) -> WeeklyPurchaseScore.fromAggregate(snapshotId, productId,
                row.getBigDecimal("total"), row.getLong("input_count"), row.getInt("minimum")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> frozenInputMismatch(WeeklyRankingOrderPartitionCommand command) {
        var params = parameters(command);
        var partition = load(params, false, "RUNNING", "RECOVERY_REQUIRED");
        try {
            verify(command, partition, params);
            return Optional.empty();
        } catch (SnapshotIntegrityException mismatch) {
            return Optional.of(mismatch.getMessage());
        }
    }

    private OrderPartition load(MapSqlParameterSource params, boolean lock, String... states) {
        // OF s avoids serializing independent freezes on their shared plan row.
        var rows = jdbc.query("""
            SELECT s.order_min,s.order_max,s.is_empty,s.local_grid,s.frozen_at,s.input_count,s.input_checksum
            FROM weekly_ranking_order_partitions s JOIN weekly_ranking_plans p ON p.snapshot_id=s.snapshot_id
            WHERE s.snapshot_id=:snapshot AND s.order_partition_id=:orderPartition AND s.state IN (:states)
              AND s.owner_id=:owner AND s.generation=:generation AND p.state='OPEN'
              AND p.period_start=:period AND p.revision=:revision
            """ + (lock ? " FOR UPDATE OF s" : ""), params.addValue("states", List.of(states)), (row, index) -> new OrderPartition(
                new OrderPartitionBounds(row.getObject("order_min", Long.class), row.getObject("order_max", Long.class)),
                row.getBoolean("is_empty"), row.getInt("local_grid"), row.getObject("frozen_at") == null
                    ? null : new InputFingerprint(row.getLong("input_count"), row.getString("input_checksum"))));
        if (rows.size() != 1) {
            throw new SnapshotConflictException("주문 파티션을 읽을 수 있는 현재 실행권이 없습니다.");
        }
        return rows.getFirst();
    }

    private void verify(WeeklyRankingOrderPartitionCommand command, OrderPartition orderPartition, MapSqlParameterSource params) {
        var expected = orderPartition.fingerprint();
        if (expected == null) {
            throw new SnapshotIntegrityException("주문 파티션의 고정 입력 manifest가 없습니다.");
        }
        var actual = fingerprint(command, orderPartition, params);
        if (!expected.equals(actual)) {
            throw new SnapshotIntegrityException("expectedCount=%d actualCount=%d expectedSha256=%s actualSha256=%s"
                .formatted(expected.count(), actual.count(), expected.sha256(), actual.sha256()));
        }
    }

    private InputFingerprint fingerprint(WeeklyRankingOrderPartitionCommand command, OrderPartition orderPartition, MapSqlParameterSource params) {
        var result = new PurchaseInputFingerprintAccumulator(command.period());
        if (!orderPartition.empty()) {
            streaming.query("""
                SELECT order_id,product_id,quantity,confirmed_at FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=:snapshot
                """ + orderPartition.bounds().predicate(params) + " ORDER BY order_id,product_id", params,
                (RowCallbackHandler) row -> result.accept(row.getLong("order_id"), row.getLong("product_id"), row.getInt("quantity"),
                    row.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
        }
        return result.finish();
    }

    private MapSqlParameterSource parameters(WeeklyRankingOrderPartitionCommand command) {
        return new MapSqlParameterSource("snapshot", command.snapshotId().toString()).addValue("orderPartition", command.orderPartitionId())
            .addValue("period", command.period().start()).addValue("revision", command.revision())
            .addValue("owner", command.ownerId()).addValue("generation", command.generation());
    }

    private record OrderPartition(OrderPartitionBounds bounds, boolean empty, int grid, InputFingerprint fingerprint) {}

    public record LocalProductPartition(int index, long firstProduct, long lastProduct, OrderPartitionBounds orderPartitionBounds) {}

    public record OrderPartitionBounds(Long min, Long max) {
        private String predicate(MapSqlParameterSource params) {
            String predicate = "";
            if (min != null) {
                params.addValue("orderMin", min);
                predicate += " AND order_id>=:orderMin";
            }
            if (max != null) {
                params.addValue("orderMax", max);
                predicate += " AND order_id<:orderMax";
            }
            return predicate;
        }
    }
}
