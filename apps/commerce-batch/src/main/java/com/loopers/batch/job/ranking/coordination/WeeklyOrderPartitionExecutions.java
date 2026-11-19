package com.loopers.batch.job.ranking.coordination;

import com.loopers.batch.job.ranking.coordination.WeeklyPlanStore.Claim;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.infrastructure.persistence.ranking.WeeklyPlanQuarantine;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class WeeklyOrderPartitionExecutions {
    private static final Logger LOG = LoggerFactory.getLogger(WeeklyOrderPartitionExecutions.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final String ACTIVE = """
        SELECT p.period_start,p.revision,s.snapshot_id,s.order_partition_id,s.owner_id,s.generation,s.execution_id,
               s.state,s.attempts,m.heartbeat_at>=TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6)) AS alive
        FROM weekly_ranking_order_partitions s JOIN weekly_ranking_plans p ON p.snapshot_id=s.snapshot_id
        LEFT JOIN weekly_ranking_members m ON m.node_id=s.owner_id AND m.session_id=s.owner_session
        WHERE p.state='OPEN' AND s.state IN ('RUNNING','RECOVERY_REQUIRED')
        """;

    private final Job job;
    private final JobLauncher launcher;
    private final JobExplorer explorer;
    private final JobRepository jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OrderPartitionRestartEvidence evidence;
    private final Duration timeout;

    WeeklyOrderPartitionExecutions(Job job, JobLauncher launcher, JobExplorer explorer, JobRepository jobs, JdbcTemplate jdbc,
                          PlatformTransactionManager manager, OrderPartitionRestartEvidence evidence, Duration timeout) {
        this.job = job;
        this.launcher = launcher;
        this.explorer = explorer;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.evidence = evidence;
        this.timeout = timeout;
    }

    void run(Claim claim) {
        try {
            launcher.run(job, parameters(claim));
        } catch (Exception exception) {
            handleLaunchFailure(claim, exception);
        }
        reconcile(claim.snapshot(), claim.orderPartitionId());
    }

    private void handleLaunchFailure(Claim claim, Exception failure) {
        boolean retry = Boolean.TRUE.equals(transactions.execute(status -> {
            var current = locked(claim.snapshot(), claim.orderPartitionId());
            if (current == null || !current.running() || !claim.equals(current.claim())) {
                return false;
            }
            var execution = latest(claim);
            if (execution != null) {
                if (execution.getStatus().isRunning()) {
                    jdbc.update("""
                        UPDATE weekly_ranking_order_partitions SET state='RECOVERY_REQUIRED',last_error='LAUNCH_OUTCOME_UNCERTAIN',
                            execution_id=COALESCE(execution_id,?) WHERE snapshot_id=? AND order_partition_id=? AND owner_id=? AND generation=?
                        """, execution.getId(), claim.snapshot().toString(), claim.orderPartitionId(), claim.owner(), claim.generation());
                }
                return false;
            }
            if (current.alive() && current.executionId() == null && isRolledBackDeadlock(failure)
                && !jobs.isJobInstanceExists(job.getName(), parameters(claim)) && evidence.matches(claim, null)) {
                if (current.attempts() < MAX_ATTEMPTS) {
                    change(current, "READY", "RETRY_JOB_CREATION_DEADLOCK");
                    return true;
                }
                change(current, "RECOVERY_REQUIRED", "JOB_CREATION_RETRY_EXHAUSTED");
            } else {
                change(current, "RECOVERY_REQUIRED", "LAUNCH_OUTCOME_UNCERTAIN");
            }
            return false;
        }));
        if (retry) {
            LOG.warn("MySQL 교착으로 롤백된 최초 Job 생성을 재시도합니다 snapshot={} orderPartitionId={} generation={}",
                claim.snapshot(), claim.orderPartitionId(), claim.generation());
        } else {
            LOG.error("주간 주문 파티션 실행 결과 확인 필요 snapshot={} orderPartitionId={}", claim.snapshot(), claim.orderPartitionId(), failure);
        }
    }

    private boolean isRolledBackDeadlock(Exception failure) {
        if (!(failure instanceof PessimisticLockingFailureException)) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    void reconcileAll() {
        var conflicts = jdbc.query("""
            SELECT p.snapshot_id FROM weekly_ranking_plans p
            WHERE (p.state='OPEN' AND EXISTS(SELECT 1 FROM weekly_ranking_score_conflicts c WHERE c.snapshot_id=p.snapshot_id))
               OR (p.state='CONFLICT' AND EXISTS(SELECT 1 FROM weekly_ranking_order_partitions s WHERE s.snapshot_id=p.snapshot_id
                                                 AND s.state IN ('READY','RUNNING','RECOVERY_REQUIRED')))
            """, (row, n) -> UUID.fromString(row.getString(1)));
        for (var snapshot : conflicts) {
            transactions.executeWithoutResult(status -> {
                var plan = jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=? FOR UPDATE",
                    String.class, snapshot.toString());
                if ("OPEN".equals(plan)) {
                    jdbc.update("UPDATE weekly_ranking_plans SET state='CONFLICT' WHERE snapshot_id=?", snapshot.toString());
                }
                if ("OPEN".equals(plan) || "CONFLICT".equals(plan)) {
                    WeeklyPlanQuarantine.blockOpenSiblings(jdbc, snapshot);
                }
            });
        }
        var active = jdbc.query(ACTIVE + """
             AND ((s.state='RUNNING' AND (m.node_id IS NULL OR m.heartbeat_at<TIMESTAMPADD(MICROSECOND,?,UTC_TIMESTAMP(6))))
                  OR EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION b WHERE b.JOB_EXECUTION_ID=s.execution_id
                             AND b.STATUS NOT IN ('STARTING','STARTED','STOPPING')))
            """, (row, n) -> new Key(UUID.fromString(row.getString(3)), row.getInt(4)), -timeout.toNanos() / 1000,
            -timeout.toNanos() / 1000);
        for (var key : active) {
            try {
                reconcile(key.snapshot(), key.orderPartitionId());
            } catch (RuntimeException failure) {
                LOG.error("주간 주문 파티션 상태를 확인할 수 없습니다 snapshot={} orderPartitionId={}", key.snapshot(), key.orderPartitionId(), failure);
            }
        }
    }

    private void reconcile(UUID snapshot, int orderPartitionId) {
        try {
            transactions.executeWithoutResult(status -> reconcileLocked(snapshot, orderPartitionId));
        } catch (FrozenInputFailure failure) {
            quarantineIfStillInvalid(failure);
        }
    }

    private void reconcileLocked(UUID snapshot, int orderPartitionId) {
        var current = locked(snapshot, orderPartitionId);
        if (current == null) {
            return;
        }
        var execution = latest(current.claim());
        if (execution == null || execution.getStatus().isRunning()) {
            if (!current.alive() && current.running()) {
                change(current, "RECOVERY_REQUIRED", "OWNER_HEARTBEAT_EXPIRED");
            }
            return;
        }
        if (current.executionId() != null && !current.executionId().equals(execution.getId())) {
            return;
        }
        if (execution.getStatus() == BatchStatus.COMPLETED) {
            change(current, "DONE", null);
        } else if (current.running() && execution.getStatus() == BatchStatus.FAILED
            && execution.getStepExecutions().stream().noneMatch(step -> step.getStatus().isRunning())) {
            if (!restartEvidenceMatches(current, execution)) {
                change(current, "RECOVERY_REQUIRED", "CHECKPOINT_EVIDENCE_MISMATCH");
            } else if (current.attempts() < MAX_ATTEMPTS) {
                change(current, "READY", "RETRY_FAILED_EXECUTION");
            } else {
                change(current, "RECOVERY_REQUIRED", "FAILED");
            }
        } else if (current.running()) {
            change(current, "RECOVERY_REQUIRED", execution.getStatus().name());
        }
    }

    void recover(UUID snapshot, int orderPartitionId, String owner, long generation, long expectedExecution, boolean terminated, String reason) {
        if (!terminated || reason == null || reason.isBlank() || reason.length() > 400) {
            throw new IllegalArgumentException("이전 프로세스의 종료 확인과 복구 근거가 필요합니다.");
        }
        boolean recovered;
        try {
            recovered = Boolean.TRUE.equals(transactions.execute(status ->
                recoverLocked(snapshot, orderPartitionId, owner, generation, expectedExecution, reason)));
        } catch (FrozenInputFailure failure) {
            quarantineIfStillInvalid(failure);
            throw failure;
        }
        if (!recovered) {
            // Report refusal only after the operator-visible recovery state has committed.
            throw new IllegalStateException("현재 고정 입력·체크포인트와 저장된 부분 점수가 일치하지 않습니다.");
        }
    }

    private boolean recoverLocked(UUID snapshot, int orderPartitionId, String owner, long generation, long expectedExecution, String reason) {
        var current = locked(snapshot, orderPartitionId);
        if (current == null || !current.claim().owner().equals(owner) || current.claim().generation() != generation) {
            throw new IllegalStateException("현재 실행권과 복구 대상이 다릅니다.");
        }
        var execution = latest(current.claim());
        if (expectedExecution != (execution == null ? 0L : execution.getId())
            || current.executionId() != null && current.executionId() != expectedExecution) {
            throw new IllegalStateException("최신 Batch execution과 복구 대상이 다릅니다.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM weekly_ranking_score_conflicts WHERE snapshot_id=? AND order_partition_id=?)
            """, Boolean.class, snapshot.toString(), orderPartitionId))) {
            throw new IllegalStateException("부분 점수 충돌이 있는 주문 파티션은 재실행할 수 없습니다.");
        }
        if (execution != null && execution.getStatus() == BatchStatus.COMPLETED) {
            change(current, "DONE", "RECONCILED " + reason);
            return true;
        }
        if (execution != null && (execution.getStatus() == BatchStatus.UNKNOWN || execution.getStatus() == BatchStatus.ABANDONED
            || execution.getStepExecutions().stream().anyMatch(step -> step.getStatus() == BatchStatus.UNKNOWN
                || step.getStatus() == BatchStatus.ABANDONED))) {
            throw new IllegalStateException("결과가 불명확한 Batch 상태는 재시작할 수 없습니다.");
        }
        // The checkpoint validator reads a RUNNING owner. This state is invisible outside this transaction.
        change(current, "RUNNING", null);
        var checkpoint = execution;
        if (checkpoint == null) {
            // A JVM can die after claiming a retry but before creating its new JobExecution.
            checkpoint = jobs.getLastJobExecution(job.getName(), parameters(current.claim()));
            if (checkpoint != null && checkpoint.getStatus() != BatchStatus.FAILED && checkpoint.getStatus() != BatchStatus.STOPPED) {
                throw new IllegalStateException("이전 세대의 Batch execution 결과를 확인해야 합니다.");
            }
        }
        if (!restartEvidenceMatches(current, checkpoint)) {
            change(current, "RECOVERY_REQUIRED", "CHECKPOINT_EVIDENCE_MISMATCH");
            return false;
        }
        if (execution != null) {
            markTerminated(execution, reason);
        }
        jdbc.update("UPDATE weekly_ranking_order_partitions SET attempts=0 WHERE snapshot_id=? AND order_partition_id=?", snapshot.toString(), orderPartitionId);
        change(current, "READY", "REQUEUED " + reason);
        return true;
    }

    private boolean restartEvidenceMatches(State current, JobExecution checkpoint) {
        try {
            return evidence.matches(current.claim(), checkpoint);
        } catch (SnapshotIntegrityException mismatch) {
            if (checkpoint == null) {
                throw mismatch;
            }
            throw new FrozenInputFailure(current, checkpoint, mismatch);
        }
    }

    private void quarantineIfStillInvalid(FrozenInputFailure failure) {
        // The evidence read rolled back. Recheck the same ownership and data before closing the whole plan.
        transactions.executeWithoutResult(status -> {
            var prior = failure.observed;
            var claim = prior.claim();
            var current = locked(claim.snapshot(), claim.orderPartitionId(), true);
            if (current == null || !current.claim().equals(claim) || !current.state().equals(prior.state())
                || !Objects.equals(current.executionId(), prior.executionId())) {
                return;
            }
            var last = jobs.getLastJobExecution(job.getName(), parameters(claim));
            if (last == null || !last.getId().equals(failure.executionId) || last.getStatus() != failure.executionStatus
                || current.executionId() != null && !current.executionId().equals(last.getId())) {
                return;
            }
            evidence.frozenInputMismatch(claim).ifPresent(detail -> {
                var reason = "FROZEN_INPUT_INTEGRITY_MISMATCH " + detail;
                change(current, "CONFLICT", reason.substring(0, Math.min(reason.length(), 512)));
                jdbc.update("UPDATE weekly_ranking_plans SET state='CONFLICT' WHERE snapshot_id=?", claim.snapshot().toString());
                WeeklyPlanQuarantine.blockOpenSiblings(jdbc, claim.snapshot());
            });
        });
    }

    private State locked(UUID snapshot, int orderPartitionId) {
        return locked(snapshot, orderPartitionId, false);
    }

    private State locked(UUID snapshot, int orderPartitionId, boolean exclusivePlan) {
        var planLock = exclusivePlan ? " FOR UPDATE" : " FOR SHARE";
        if (!"OPEN".equals(jdbc.queryForObject("SELECT state FROM weekly_ranking_plans WHERE snapshot_id=?" + planLock,
            String.class, snapshot.toString()))) {
            return null;
        }
        return jdbc.query(ACTIVE + " AND s.snapshot_id=? AND s.order_partition_id=? FOR UPDATE OF s", (row, n) -> new State(
            new Claim(row.getObject(1, LocalDate.class), row.getInt(2), snapshot, orderPartitionId, row.getString(5), row.getLong(6)),
            row.getObject(7, Long.class), row.getString(8), row.getInt(9), row.getBoolean(10)),
            -timeout.toNanos() / 1000, snapshot.toString(), orderPartitionId).stream().findFirst().orElse(null);
    }

    private JobExecution latest(Claim claim) {
        var last = jobs.getLastJobExecution(job.getName(), parameters(claim));
        if (last == null || !Objects.equals(claim.owner(), last.getJobParameters().getString("ownerId"))
            || !Objects.equals(claim.generation(), last.getJobParameters().getLong("generation"))) {
            return null;
        }
        return explorer.getJobExecution(last.getId());
    }

    private JobParameters parameters(Claim claim) {
        return new JobParametersBuilder().addString("snapshotId", claim.snapshot().toString()).addLong("orderPartitionId", (long) claim.orderPartitionId())
            .addString("periodStart", claim.period().toString(), false).addLong("revision", (long) claim.revision(), false)
            .addString("ownerId", claim.owner(), false).addLong("generation", claim.generation(), false).toJobParameters();
    }

    private void markTerminated(JobExecution execution, String reason) {
        var end = LocalDateTime.now();
        var exit = ExitStatus.FAILED.addExitDescription("TERMINATION_CONFIRMED " + reason);
        for (var step : execution.getStepExecutions()) {
            if (step.getStatus().isRunning()) {
                step.setStatus(BatchStatus.FAILED);
                step.setExitStatus(exit);
                step.setEndTime(end);
                jobs.update(step);
            }
        }
        if (execution.getStatus().isRunning()) {
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(exit);
            execution.setEndTime(end);
            jobs.update(execution);
        }
    }

    private void change(State current, String state, String reason) {
        var claim = current.claim();
        jdbc.update("""
            UPDATE weekly_ranking_order_partitions SET state=?,last_error=?
            WHERE snapshot_id=? AND order_partition_id=? AND owner_id=? AND generation=?
            """, state, reason, claim.snapshot().toString(), claim.orderPartitionId(), claim.owner(), claim.generation());
    }

    private record State(Claim claim, Long executionId, String state, int attempts, boolean alive) {
        boolean running() {
            return "RUNNING".equals(state);
        }
    }

    private static final class FrozenInputFailure extends SnapshotIntegrityException {
        private final State observed;
        private final Long executionId;
        private final BatchStatus executionStatus;

        private FrozenInputFailure(State observed, JobExecution checkpoint, SnapshotIntegrityException mismatch) {
            super(mismatch.getMessage());
            initCause(mismatch);
            this.observed = observed;
            this.executionId = checkpoint.getId();
            this.executionStatus = checkpoint.getStatus();
        }
    }

    private record Key(UUID snapshot, int orderPartitionId) { }
}
