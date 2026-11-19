package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchException.Reason;
import com.loopers.batch.job.weeklyranking.step.PublishWeeklyRankingTasklet;
import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.RankingLaunchRepository;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobInstanceAlreadyExistsException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.env.Environment;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * weeklyRanking 의 new/restart 명시 실행(랭킹 설계 4.4~4.9). 상위 TX 없이 기존 JobOperator 를 호출하고
 * 영속 상태가 COMPLETED 일 때만 종료 코드 0 을 낸다. 확인한 거절·실패는 1, 그 밖의 기술 예외는 전파한다.
 */
@Slf4j
@RequiredArgsConstructor
public class WeeklyRankingLaunchRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Set<BatchStatus> ACTIVE_OR_UNKNOWN =
        EnumSet.of(BatchStatus.STARTING, BatchStatus.STARTED, BatchStatus.STOPPING, BatchStatus.UNKNOWN);
    private static final int HISTORY_PAGE = 100;

    private final WeeklyRankingLaunchProperties properties;
    private final JobOperator jobOperator;
    private final JobExplorer jobExplorer;
    private final JobRegistry jobRegistry;
    private final Job weeklyRankingJob;
    private final RankingLaunchRepository launchRepository;
    private final Environment environment;

    @Getter
    private LaunchResult result = LaunchResult.rejected(null);

    /** publicationOutcome 은 같은 instance 의 마지막 공개 Step 이 대상 snapshot 에 남긴 값이 있을 때만 채운다. */
    public record LaunchResult(
        int exitCode, Reason rejection, Long executionId, Long instanceId, BatchStatus status, String publicationOutcome
    ) {
        static LaunchResult rejected(Reason reason) {
            return new LaunchResult(1, reason, null, null, null, null);
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            checkPreconditions(args);
            String mode = properties.mode();
            if ("new".equals(mode)) {
                result = verify(startNew(), null);
            } else if ("restart".equals(mode)) {
                JobExecution target = checkRestartTarget();
                result = verify(restart(target.getId()), target);
            } else {
                throw reject(Reason.INVALID_OPTION, "weekly-ranking.mode 는 new 또는 restart 여야 합니다.");
            }
        } catch (WeeklyRankingLaunchException e) {
            log.error("weeklyRanking 실행 거절 reason={} message={}", e.getReason(), e.getMessage());
            result = LaunchResult.rejected(e.getReason());
        }
    }

    @Override
    public int getExitCode() {
        return result.exitCode();
    }

    private void checkPreconditions(ApplicationArguments args) throws NoSuchJobException {
        if (!args.getNonOptionArgs().isEmpty()) {
            throw reject(Reason.INVALID_OPTION, "옵션이 아닌 인자는 받지 않습니다.");
        }
        if (!"false".equals(environment.getProperty("spring.batch.job.enabled"))) {
            throw reject(Reason.INVALID_OPTION, "spring.batch.job.enabled=false 가 필요합니다.");
        }
        if (jobRegistry.getJob(WeeklyRankingJobConfig.JOB_NAME) != weeklyRankingJob) {
            throw reject(Reason.INVALID_OPTION, "JobRegistry 의 weeklyRanking 이 조립한 Job 과 다릅니다.");
        }
        boolean isNew = "new".equals(properties.mode());
        if (isNew && (properties.periodStart() == null || properties.executionId() != null)) {
            throw reject(Reason.INVALID_OPTION, "new 는 period-start 만 받습니다.");
        }
        if ("restart".equals(properties.mode()) && (properties.executionId() == null || properties.periodStart() != null)) {
            throw reject(Reason.INVALID_OPTION, "restart 는 execution-id 만 받습니다.");
        }
    }

    private long startNew() throws Exception {
        LocalDate periodStart = parsePeriodStart(properties.periodStart());
        long historyMax = scanHistory(periodStart);
        OptionalLong storedMax = launchRepository.findMaxSnapshotRevision(periodStart);
        if (storedMax.isPresent() && storedMax.getAsLong() <= 0) {
            throw reject(Reason.CORRUPT_HISTORY, "snapshot 최대 revision 이 양수가 아닙니다: " + storedMax.getAsLong());
        }
        long snapshotMax = storedMax.orElse(0L);
        long revision = Math.addExact(Math.max(historyMax, snapshotMax), 1L);
        WeeklyRankingRequest request = WeeklyRankingRequest.of(
            periodStart.toString(), revision, WeeklyRankingRequest.canonicalId(periodStart, revision));
        JobParameters parameters = WeeklyRankingParameters.toJobParameters(request);
        WeeklyRankingParameters.VALIDATOR.validate(parameters);
        log.info("weeklyRanking new periodStart={} revision={} snapshotId={}", periodStart, revision, request.snapshotId());
        try {
            return jobOperator.start(WeeklyRankingJobConfig.JOB_NAME,
                new DefaultJobParametersConverter().getProperties(parameters));
        } catch (JobInstanceAlreadyExistsException | JobParametersInvalidException e) {
            throw reject(Reason.LAUNCH_REJECTED, e.getMessage());
        }
    }

    private JobExecution checkRestartTarget() {
        long executionId = properties.executionId();
        JobExecution target = executionId > 0 ? jobExplorer.getJobExecution(executionId) : null;
        if (target == null || !WeeklyRankingJobConfig.JOB_NAME.equals(target.getJobInstance().getJobName())) {
            throw reject(Reason.TARGET_NOT_FOUND, "weeklyRanking 실행 " + executionId + " 이 없습니다.");
        }
        JobExecution latest = jobExplorer.getLastJobExecution(target.getJobInstance());
        if (latest == null || !latest.getId().equals(target.getId())) {
            throw reject(Reason.NOT_LATEST_EXECUTION, "같은 instance 의 최신 실행 ID 는 "
                + (latest == null ? "없음" : latest.getId()) + " 입니다.");
        }
        WeeklyRankingRequest request = parse(target.getJobParameters());
        boolean stepActive = target.getStepExecutions().stream()
            .anyMatch(step -> ACTIVE_OR_UNKNOWN.contains(step.getStatus()) || step.getStatus() == BatchStatus.ABANDONED);
        if (ACTIVE_OR_UNKNOWN.contains(target.getStatus()) || stepActive) {
            throw reject(Reason.IN_PROGRESS_OR_UNKNOWN, "진행 중·UNKNOWN 실행은 상태 확인 전 재시작하지 않습니다.");
        }
        if (target.getStatus() != BatchStatus.FAILED && target.getStatus() != BatchStatus.STOPPED) {
            throw reject(Reason.NOT_RESTARTABLE, "FAILED·STOPPED 실행만 재시작합니다: " + target.getStatus());
        }
        scanHistory(request.periodStart());
        return target;
    }

    private long restart(long executionId) throws Exception {
        log.info("weeklyRanking restart executionId={}", executionId);
        try {
            return jobOperator.restart(executionId);
        } catch (JobInstanceAlreadyCompleteException | NoSuchJobExecutionException | JobRestartException
                 | JobParametersInvalidException e) {
            throw reject(Reason.LAUNCH_REJECTED, e.getMessage());
        }
    }

    /** 같은 기간의 등록 revision 최댓값. 진행 중·UNKNOWN 실행이나 손상된 이력이 있으면 거절한다. */
    private long scanHistory(LocalDate periodStart) {
        long max = 0;
        for (int start = 0; ; start += HISTORY_PAGE) {
            List<JobInstance> instances = jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, start, HISTORY_PAGE);
            if (instances.isEmpty()) {
                return max;
            }
            for (JobInstance instance : instances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                if (executions.isEmpty()) {
                    throw reject(Reason.CORRUPT_HISTORY, "실행 없는 instance " + instance.getId() + " 가 있습니다.");
                }
                WeeklyRankingRequest request = parse(executions.get(0).getJobParameters());
                if (!request.periodStart().equals(periodStart)) {
                    continue;
                }
                max = Math.max(max, request.revision());
                if (executions.stream().anyMatch(execution -> ACTIVE_OR_UNKNOWN.contains(execution.getStatus()))) {
                    throw reject(Reason.IN_PROGRESS_OR_UNKNOWN,
                        "같은 기간에 진행 중·UNKNOWN 실행이 있습니다: instance " + instance.getId());
                }
            }
        }
    }

    private LaunchResult verify(long executionId, JobExecution restartTarget) {
        JobExecution fresh = jobExplorer.getJobExecution(executionId);
        if (fresh == null) {
            throw reject(Reason.TARGET_NOT_FOUND, "실행 " + executionId + " 을 다시 읽지 못했습니다.");
        }
        if (restartTarget != null && (!fresh.getJobInstance().getId().equals(restartTarget.getJobInstance().getId())
            || !fresh.getJobParameters().getIdentifyingParameters()
                .equals(restartTarget.getJobParameters().getIdentifyingParameters()))) {
            throw reject(Reason.LAUNCH_REJECTED, "재시작 실행이 대상 instance·parameter 와 다릅니다.");
        }
        BatchStatus status = fresh.getStatus();
        String snapshotId = fresh.getJobParameters().getString(WeeklyRankingParameters.SNAPSHOT_ID);
        // 같은 instance 의 마지막 공개 Step context 를 쓴다. 재시작에서 완료 Step 을 건너뛰어도 저장된 값만 읽고 만들어 쓰지 않는다.
        String outcome = jobExplorer.getJobExecutions(fresh.getJobInstance()).stream()
            .flatMap(execution -> execution.getStepExecutions().stream())
            .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP))
            .max(Comparator.comparing(StepExecution::getId))
            .map(StepExecution::getExecutionContext)
            .filter(context -> Objects.equals(snapshotId, context.getString(PublishWeeklyRankingTasklet.TARGET_KEY, null)))
            .map(context -> context.getString(PublishWeeklyRankingTasklet.OUTCOME_KEY, null))
            .orElse(null);
        log.info("weeklyRanking 결과 instanceId={} executionId={} parameters={} status={} publicationOutcome={}",
            fresh.getJobInstance().getId(), executionId, fresh.getJobParameters(), status, outcome);
        return new LaunchResult(status == BatchStatus.COMPLETED ? 0 : 1, null, executionId,
            fresh.getJobInstance().getId(), status, outcome);
    }

    private static LocalDate parsePeriodStart(String raw) {
        try {
            return WeeklyRankingRequest.parsePeriodStart(raw);
        } catch (RankingInputException e) {
            throw reject(Reason.INVALID_OPTION, e.getMessage());
        }
    }

    private static WeeklyRankingRequest parse(JobParameters parameters) {
        try {
            return WeeklyRankingParameters.parse(parameters);
        } catch (RankingInputException e) {
            throw reject(Reason.CORRUPT_HISTORY, "식별 parameter 가 손상됐습니다: " + e.getMessage());
        }
    }

    private static WeeklyRankingLaunchException reject(Reason reason, String message) {
        return new WeeklyRankingLaunchException(reason, message);
    }
}
