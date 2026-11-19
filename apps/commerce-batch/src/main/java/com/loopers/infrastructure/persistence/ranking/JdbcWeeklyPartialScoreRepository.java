package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.domain.ranking.repository.WeeklyPartialScoreRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcWeeklyPartialScoreRepository implements WeeklyPartialScoreRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate auditTransaction;

    public JdbcWeeklyPartialScoreRepository(DataSource source, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(source);
        auditTransaction = new TransactionTemplate(manager);
        auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void lockActiveOwner(UUID snapshotId, int orderPartitionId, String ownerId, long generation) {
        String id = snapshotId.toString();
        // Finalization and quarantine also lock the plan before its order partitions. Readers can hold these locks concurrently.
        var open = jdbc.queryForList("""
            SELECT snapshot_id FROM weekly_ranking_plans WHERE snapshot_id=? AND state='OPEN' FOR SHARE
            """, String.class, id);
        if (open.isEmpty()) {
            throw new SnapshotConflictException("닫힌 주간 계획에는 부분 점수를 쓸 수 없습니다: " + snapshotId);
        }
        var owners = jdbc.queryForList("""
            SELECT order_partition_id FROM weekly_ranking_order_partitions WHERE snapshot_id=? AND order_partition_id=?
              AND state='RUNNING' AND owner_id=? AND generation=? AND frozen_at IS NOT NULL FOR SHARE
            """, Integer.class, id, orderPartitionId, ownerId, generation);
        if (owners.isEmpty()) {
            throw new SnapshotConflictException("실행권이 없거나 고정되지 않은 주문 파티션입니다: " + orderPartitionId);
        }
    }

    @Override
    public Map<Long, Long> insertAndRead(UUID snapshotId, int orderPartitionId, List<? extends WeeklyPurchaseScore> scores) {
        String id = snapshotId.toString();
        jdbc.batchUpdate("""
            INSERT INTO weekly_ranking_partial_scores (snapshot_id,order_partition_id,product_id,score) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE score=weekly_ranking_partial_scores.score
            """, scores, scores.size(), (statement, score) -> {
                statement.setString(1, id);
                statement.setInt(2, orderPartitionId);
                statement.setLong(3, score.productId());
                statement.setLong(4, score.score());
            });
        String products = scores.stream().map(score -> Long.toString(score.productId())).collect(Collectors.joining(",", "[", "]"));
        var result = new HashMap<Long, Long>();
        // A duplicate no-op can be invisible to an older RR snapshot. Read current rows by full primary key only.
        jdbc.query("""
            SELECT /*+ NO_BNL(p) */ p.product_id,p.score
            FROM JSON_TABLE(?, '$[*]' COLUMNS(product_id BIGINT PATH '$')) AS requested
            STRAIGHT_JOIN weekly_ranking_partial_scores AS p FORCE INDEX(PRIMARY)
              ON p.snapshot_id=? AND p.order_partition_id=? AND p.product_id=requested.product_id
            FOR SHARE OF p
            """, (RowCallbackHandler) row -> result.put(row.getLong("product_id"), row.getLong("score")), products, id, orderPartitionId);
        return result;
    }

    @Override
    public void recordConflict(int orderPartitionId, String ownerId, long generation, WeeklyPurchaseScore attempted, long stored) {
        // Append before the worker transaction rolls back. This independent insert only shares the parent FK lock.
        auditTransaction.executeWithoutResult(status -> jdbc.update("""
            INSERT INTO weekly_ranking_score_conflicts
              (snapshot_id,order_partition_id,product_id,stored_score,attempted_score,owner_id,generation,recorded_at)
            VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE stored_score=weekly_ranking_score_conflicts.stored_score
            """, attempted.snapshotId().toString(), orderPartitionId, attempted.productId(), stored, attempted.score(), ownerId, generation));
    }
}
