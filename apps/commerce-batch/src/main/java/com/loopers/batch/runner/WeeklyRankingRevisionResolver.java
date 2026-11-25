package com.loopers.batch.runner;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * 사람이 revision 번호를 넣지 않게, 명령의 periodStart와 "재계산인가"로 revision을 정한다 (W9 D-8).
 * "가장 큰 revision"은 그 주의 Batch 기록에서 찾는다 — 입력 고정 step 전에 죽으면 snapshot 행이 없을 수 있기 때문이다.
 * <pre>
 * 재계산 아님  그 주 작업 없음 → 1 / 있음 → 가장 큰 revision 그대로 (FAILED면 Boot가 재시작, COMPLETED면 Spring Batch가 거절)
 * 재계산      그 주 작업 없음 → 거절 / 실행 중·멈춤 → 거절 (D-5 같은 주는 하나씩)
 *            가장 큰 revision에 snapshot이 없음 → 거절, 재시작하라고 안내 (입력 고정 step의 "가장 큰 revision + 1"과 어긋나지 않게)
 *            그 밖 → 가장 큰 revision + 1
 * </pre>
 */
@Component
public class WeeklyRankingRevisionResolver {

    private final JobExplorer jobExplorer;
    private final JdbcTemplate jdbc;

    public WeeklyRankingRevisionResolver(JobExplorer jobExplorer, DataSource dataSource) {
        this.jobExplorer = jobExplorer;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long resolve(LocalDate periodStart, boolean recompute) {
        Optional<JobExecution> latest = latestOfMaxRevision(periodStart);
        if (!recompute) {
            return latest.map(WeeklyRankingRevisionResolver::revisionOf).orElse(1L);
        }
        JobExecution execution = latest.orElseThrow(() -> new IllegalStateException(
            "재계산할 원래 작업이 없습니다. --recompute 없이 실행하세요. [periodStart = " + periodStart + "]"));
        long maxRevision = revisionOf(execution);
        if (execution.isRunning() || execution.getStatus() == BatchStatus.UNKNOWN) {
            throw new IllegalStateException("r" + maxRevision + " 작업이 아직 실행 중이거나 멈춰 있습니다(" + execution.getStatus()
                + "). 끝나기를 기다리거나 멈춘 실행을 정리한 뒤(runbook/mark-execution-failed.sql) 다시 실행하세요. [periodStart = " + periodStart + "]");
        }
        if (!snapshotExists(periodStart, maxRevision)) {
            throw new IllegalStateException("r" + maxRevision + " 작업은 입력을 고정하기 전에 실패해 snapshot이 없습니다. "
                + "재계산이 아니라 --recompute 없이 재시작하세요. [periodStart = " + periodStart + "]");
        }
        return maxRevision + 1;
    }

    private Optional<JobExecution> latestOfMaxRevision(LocalDate periodStart) {
        return jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, Integer.MAX_VALUE).stream()
            .map(this::lastExecution)
            .filter(Objects::nonNull)
            .filter(execution -> periodStart.equals(execution.getJobParameters().getLocalDate(WeeklyRankingJobParametersValidator.PERIOD_START)))
            .max(Comparator.comparingLong(WeeklyRankingRevisionResolver::revisionOf));
    }

    private JobExecution lastExecution(JobInstance instance) {
        return jobExplorer.getLastJobExecution(instance);
    }

    private boolean snapshotExists(LocalDate periodStart, long revision) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot WHERE period_start = ? AND revision = ?", Integer.class, periodStart, revision);
        return count != null && count > 0;
    }

    private static long revisionOf(JobExecution execution) {
        JobParameters parameters = execution.getJobParameters();
        Long revision = parameters.getLong(WeeklyRankingJobParametersValidator.REVISION);
        return revision == null ? 0 : revision;
    }
}
