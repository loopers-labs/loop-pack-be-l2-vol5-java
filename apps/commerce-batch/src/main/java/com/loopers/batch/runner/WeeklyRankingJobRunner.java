package com.loopers.batch.runner;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParametersValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 실행기(Jenkins·cron·Temporal 등)가 부르는 명령의 입구 (W9 D-8). settlement 예제의 JobScheduler 자리다.
 * <pre>
 * 정기·재시작  --job.name=weeklyRankingJob periodStart=2026-08-17
 * 재계산      --job.name=weeklyRankingJob periodStart=2026-08-17 --recompute=true
 * </pre>
 * 명령의 periodStart는 타입 표시 없이 문자열로 들어온다. 이를 LocalDate로 바꾸고 revision을 정해 넣은 뒤,
 * 재시작 판단(마지막 실행이 FAILED면 같은 작업을 이어 감)과 종료 코드는 Boot 원래 동작을 그대로 쓴다.
 */
@Slf4j
public class WeeklyRankingJobRunner extends JobLauncherApplicationRunner {

    private final WeeklyRankingRevisionResolver revisionResolver;
    private final boolean recompute;

    public WeeklyRankingJobRunner(JobLauncher jobLauncher, JobExplorer jobExplorer, JobRepository jobRepository,
                                  WeeklyRankingRevisionResolver revisionResolver, boolean recompute) {
        super(jobLauncher, jobExplorer, jobRepository);
        this.revisionResolver = revisionResolver;
        this.recompute = recompute;
    }

    @Override
    protected void execute(Job job, JobParameters jobParameters) throws JobExecutionAlreadyRunningException,
        JobRestartException, JobInstanceAlreadyCompleteException, JobParametersInvalidException {
        if (!WeeklyRankingJobConfig.JOB_NAME.equals(job.getName())) {
            super.execute(job, jobParameters);
            return;
        }
        LocalDate periodStart = periodStart(jobParameters);
        long revision = revisionResolver.resolve(periodStart, recompute);
        log.info("[주간 랭킹] periodStart = {}, 재계산 = {} → revision = {}", periodStart, recompute, revision);
        super.execute(job, new JobParametersBuilder()
            .addLocalDate(WeeklyRankingJobParametersValidator.PERIOD_START, periodStart)
            .addLong(WeeklyRankingJobParametersValidator.REVISION, revision)
            .toJobParameters());
    }

    private static LocalDate periodStart(JobParameters jobParameters) throws JobParametersInvalidException {
        JobParameter<?> parameter = jobParameters.getParameter(WeeklyRankingJobParametersValidator.PERIOD_START);
        if (parameter == null) {
            throw new JobParametersInvalidException("periodStart가 필요합니다. 예: periodStart=2026-08-17");
        }
        if (parameter.getValue() instanceof LocalDate date) {
            return date;
        }
        try {
            return LocalDate.parse(String.valueOf(parameter.getValue()));
        } catch (DateTimeParseException e) {
            throw new JobParametersInvalidException("periodStart는 yyyy-MM-dd 형식이어야 합니다. [periodStart = " + parameter.getValue() + "]");
        }
    }
}
