package com.loopers.infrastructure.persistence.ranking;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class WeeklyPlanQuarantine {
    private WeeklyPlanQuarantine() { }

    /** Called while the conflicting plan is exclusively locked by the current transaction. */
    public static void blockOpenSiblings(JdbcTemplate jdbc, UUID snapshot) {
        jdbc.update("""
            UPDATE weekly_ranking_order_partitions s SET state='CONFLICT',last_error='SCORE_CONFLICT'
            WHERE snapshot_id=? AND state<>'CONFLICT' AND EXISTS (
                SELECT 1 FROM weekly_ranking_score_conflicts c WHERE c.snapshot_id=s.snapshot_id AND c.order_partition_id=s.order_partition_id)
            """, snapshot.toString());
        jdbc.update("""
            UPDATE weekly_ranking_order_partitions SET state='BLOCKED',last_error='PLAN_CONFLICT'
            WHERE snapshot_id=? AND state IN ('READY','RUNNING','RECOVERY_REQUIRED')
            """, snapshot.toString());
    }
}
