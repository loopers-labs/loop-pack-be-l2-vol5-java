package com.loopers.batch.job.ranking.coordination;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class WeeklyPlanStore {
    private static final String ELIGIBLE = """
        FROM weekly_ranking_order_partitions s JOIN weekly_ranking_plans p ON p.snapshot_id=s.snapshot_id
        LEFT JOIN weekly_ranking_members m ON m.node_id=s.preferred_node
        WHERE s.state='READY' AND p.state='OPEN' AND
            (s.preferred_node=? OR m.heartbeat_at IS NULL OR m.heartbeat_at<TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)))
        """;
    private static final String PREFERENCE = " ORDER BY (s.preferred_node=?) DESC,p.period_start,p.revision,s.order_partition_id LIMIT 1";

    private final String session = UUID.randomUUID().toString();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    WeeklyPlanStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    void heartbeat(String node, int grid) {
        int matched = jdbc.update("""
            UPDATE weekly_ranking_members SET heartbeat_at=UTC_TIMESTAMP(6),local_grid=? WHERE node_id=? AND session_id=?
            """, grid, node, session);
        if (matched != 1) {
            throw new IllegalStateException("현재 프로세스가 등록한 랭킹 노드 ID가 아닙니다: " + node);
        }
    }

    void register(String node, int grid, Duration timeout) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO weekly_ranking_members(node_id,session_id,heartbeat_at,local_grid) VALUES (?,?,UTC_TIMESTAMP(6),?)
                ON DUPLICATE KEY UPDATE node_id=node_id
                """, node, session, grid);
            int matched = jdbc.update("""
                UPDATE weekly_ranking_members SET session_id=?,heartbeat_at=UTC_TIMESTAMP(6),local_grid=?
                WHERE node_id=? AND (session_id=? OR heartbeat_at<TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)))
                """, session, grid, node, session, -timeout.toNanos() / 1000);
            if (matched != 1) {
                throw new IllegalStateException("같은 노드 ID를 사용하는 활성 프로세스가 있습니다: " + node);
            }
        });
    }

    LocalDate firstDue(LocalDate latest) {
        LocalDate last = jdbc.queryForObject("""
            SELECT MAX(last_period) FROM weekly_ranking_schedule_cursor WHERE last_period<=?
            """, LocalDate.class, latest);
        return last == null ? latest : last.plusWeeks(1);
    }

    UUID ensurePlan(LocalDate start, int revision, Duration timeout, boolean automatic) {
        var period = new RankingPeriod(start);
        if (revision < 1) {
            throw new IllegalArgumentException("revision은 양수여야 합니다.");
        }
        return transactions.execute(status -> {
            jdbc.update("""
                INSERT INTO weekly_ranking_publications(period_start) VALUES (?)
                ON DUPLICATE KEY UPDATE period_start=period_start
                """, start);
            var pointer = jdbc.queryForObject("""
                SELECT COALESCE(revision,0) FROM weekly_ranking_publications WHERE period_start=? FOR UPDATE
                """, Integer.class, start);
            var existing = jdbc.query("""
                SELECT m.snapshot_id,p.snapshot_id IS NOT NULL,c.snapshot_id IS NOT NULL
                FROM weekly_ranking_snapshots m LEFT JOIN weekly_ranking_plans p ON p.snapshot_id=m.snapshot_id
                LEFT JOIN weekly_ranking_completions c ON c.snapshot_id=m.snapshot_id
                WHERE m.period_start=? AND m.revision=?
                """, (row, n) -> new Existing(UUID.fromString(row.getString(1)), row.getBoolean(2), row.getBoolean(3)), start, revision);
            if (!existing.isEmpty()) {
                var found = existing.getFirst();
                if (!found.plan() && !(found.complete() && pointer >= revision)) {
                    throw new IllegalStateException("기존 단일 배치 snapshot이 미공개 상태입니다. 같은 식별값으로 기존 작업을 완료하십시오: "
                        + found.id());
                }
                return acknowledge(start, found.id(), automatic);
            }
            var previous = jdbc.query("""
                SELECT m.revision,p.state FROM weekly_ranking_snapshots m LEFT JOIN weekly_ranking_plans p ON p.snapshot_id=m.snapshot_id
                WHERE m.period_start=? ORDER BY m.revision DESC LIMIT 1
                """, (row, n) -> new Previous(row.getInt(1), row.getString(2)), start).stream().findFirst();
            long next = previous.map(last -> (long) last.revision() + 1).orElse(1L);
            if (revision != next || previous.isPresent() && !previous.get().allowsNext(pointer, automatic)) {
                throw new IllegalStateException("이전 revision이 종료된 뒤 다음 revision 번호를 명시해야 합니다.");
            }
            var members = jdbc.query("""
                SELECT node_id,local_grid FROM weekly_ranking_members
                WHERE heartbeat_at >= TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)) ORDER BY node_id
                """, (row, n) -> new Member(row.getString(1), row.getInt(2)), -timeout.toNanos() / 1000);
            if (members.isEmpty()) {
                throw new IllegalStateException("계획을 구성할 활성 랭킹 인스턴스가 없습니다.");
            }
            UUID snapshot = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshots(snapshot_id,period_start,period_end,revision,state,input_contract_version)
                VALUES (?,?,?,?,'FREEZING',2)
                """, snapshot.toString(), start, period.end(), revision);
            jdbc.update("""
                INSERT INTO weekly_ranking_plans(snapshot_id,period_start,revision,order_partition_count) VALUES (?,?,?,?)
                """, snapshot.toString(), start, revision, members.size());
            var starts = jdbc.queryForList("""
                WITH weights AS (
                    SELECT order_id,COUNT(*) AS weight FROM product_purchase_activities
                    WHERE confirmed_at>=? AND confirmed_at<? GROUP BY order_id
                ), totals AS (
                    SELECT order_id,weight,SUM(weight) OVER (ORDER BY order_id) AS cumulative,SUM(weight) OVER () AS total
                    FROM weights
                ), buckets AS (
                    SELECT order_id,FLOOR(((2*cumulative-weight)*?)/(2*total)) AS bucket FROM totals
                )
                SELECT MIN(order_id) FROM buckets GROUP BY bucket ORDER BY bucket
                """, Long.class, start.atStartOfDay(), period.end().atStartOfDay(), members.size());
            for (int i = 0; i < members.size(); i++) {
                boolean empty = i >= Math.max(1, starts.size());
                Long lower = !empty && i > 0 ? starts.get(i) : null;
                Long upper = i + 1 < starts.size() ? starts.get(i + 1) : null;
                var member = members.get(i);
                jdbc.update("""
                    INSERT INTO weekly_ranking_order_partitions(snapshot_id,order_partition_id,order_min,order_max,is_empty,preferred_node,local_grid)
                    VALUES (?,?,?,?,?,?,?)
                    """, snapshot.toString(), i, lower, upper, empty, member.id(), member.grid());
            }
            return acknowledge(start, snapshot, automatic);
        });
    }

    private UUID acknowledge(LocalDate week, UUID snapshot, boolean automatic) {
        if (automatic) {
            jdbc.update("""
                INSERT INTO weekly_ranking_schedule_cursor(schedule_id,last_period) VALUES (1,?)
                ON DUPLICATE KEY UPDATE last_period=GREATEST(last_period,?)
                """, week, week);
        }
        return snapshot;
    }

    Optional<Claim> claim(String node, Duration timeout) {
        return transactions.execute(status -> {
            var active = jdbc.query("""
                SELECT node_id FROM weekly_ranking_members WHERE node_id=? AND session_id=?
                  AND heartbeat_at>=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)) FOR SHARE
                """, (row, n) -> row.getString(1), node, session, -timeout.toNanos() / 1000);
            if (active.isEmpty()) {
                return Optional.empty();
            }
            long cutoff = -timeout.toNanos() / 1000;
            var plan = jdbc.query("SELECT s.snapshot_id " + ELIGIBLE + PREFERENCE,
                (row, n) -> row.getString(1), node, cutoff, node).stream().findFirst();
            if (plan.isEmpty() || !"OPEN".equals(jdbc.queryForObject(
                "SELECT state FROM weekly_ranking_plans WHERE snapshot_id=? FOR SHARE", String.class, plan.get()))) {
                return Optional.empty();
            }
            var candidate = jdbc.query("SELECT p.period_start,p.revision,s.snapshot_id,s.order_partition_id,s.generation "
                + ELIGIBLE + " AND p.snapshot_id=?" + PREFERENCE + " FOR UPDATE OF s SKIP LOCKED",
                (row, n) -> new Claim(row.getObject(1, LocalDate.class), row.getInt(2), UUID.fromString(row.getString(3)),
                    row.getInt(4), node, row.getLong(5) + 1), node, cutoff, plan.get(), node).stream().findFirst();
            candidate.ifPresent(claim -> jdbc.update("""
                UPDATE weekly_ranking_order_partitions SET state='RUNNING',owner_id=?,owner_session=?,generation=?,execution_id=NULL,attempts=attempts+1
                WHERE snapshot_id=? AND order_partition_id=?
                """, node, session, claim.generation(), claim.snapshot().toString(), claim.orderPartitionId()));
            return candidate;
        });
    }

    List<UUID> publishable() {
        return jdbc.query("""
            SELECT p.snapshot_id FROM weekly_ranking_plans p WHERE p.state='OPEN' AND p.order_partition_count=(
                SELECT COUNT(*) FROM weekly_ranking_order_partitions s WHERE s.snapshot_id=p.snapshot_id AND s.state='DONE'
            ) ORDER BY p.period_start,p.revision
            """, (row, n) -> UUID.fromString(row.getString(1)));
    }

    record Claim(LocalDate period, int revision, UUID snapshot, int orderPartitionId, String owner, long generation) { }

    private record Previous(int revision, String state) {
        boolean allowsNext(int published, boolean automatic) {
            return state == null ? published >= revision : !"OPEN".equals(state)
                && (published >= revision || !automatic && "CONFLICT".equals(state));
        }
    }

    private record Existing(UUID id, boolean plan, boolean complete) { }

    private record Member(String id, int grid) { }
}
