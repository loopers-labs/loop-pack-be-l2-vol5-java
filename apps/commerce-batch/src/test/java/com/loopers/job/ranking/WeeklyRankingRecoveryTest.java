package com.loopers.job.ranking;

import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.ranking.WeeklyRankingRecovery;
import com.loopers.batch.job.ranking.WeeklyRankingRecoveryRunner;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingRecoveryTest extends WeeklyRankingJobTestSupport {
    @Autowired private WeeklyRankingRecovery recovery;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("종료 확인이 없는 STARTED 실행은 같은 기간의 새 작업을 막는다")
    void startedExecutionBlocksNewInstance() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);

        JobExecution next = launch(snapshot(2));

        assertThat(next.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("STARTED 실행이 남아 있으면 같은 기간 실행은 동시 실행 제한으로 실패한다")
    void startedExecutionFailsNewInstanceForSamePeriod() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);

        JobExecution next = launch(snapshot(2));

        assertThat(next.getAllFailureExceptions())
                .anySatisfy(error -> assertThat(error).hasMessageContaining("already running"));
    }

    @Test
    @DisplayName("STARTED로 남은 기존 JobInstance도 다시 시작할 수 없다")
    void startedExecutionBlocksSameInstance() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);

        assertThatThrownBy(() -> launch(snapshot(1)))
                .isInstanceOf(JobExecutionAlreadyRunningException.class);
    }

    @Test
    @DisplayName("운영자가 종료를 확인하고 이전 실행을 포기하면 새 작업을 실행할 수 있다")
    void confirmedStoppedExecutionAllowsNewInstance() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);

        recovery.abandonAfterTerminationConfirmed(previous.getId());
        JobExecution next = launch(snapshot(2));

        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("종료 확인 후 포기한 기존 JobInstance는 재시작할 수 없다")
    void abandonedInstanceCannotRestart() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);
        recovery.abandonAfterTerminationConfirmed(previous.getId());

        assertThatThrownBy(() -> launch(snapshot(1)))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @DisplayName("종료된 실행을 포기하면 실행 중으로 남은 Step도 포기 상태가 된다")
    void recoveryAbandonsStartedStep() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);
        StepExecution step = previous.createStepExecution("calculateWeeklyScores");
        step.setStatus(BatchStatus.STARTED);
        jobRepository.add(step);

        recovery.abandonAfterTerminationConfirmed(previous.getId());

        assertThat(jobExplorer.getStepExecution(previous.getId(), step.getId()).getStatus())
                .isEqualTo(BatchStatus.ABANDONED);
    }

    @Test
    @DisplayName("복구 트랜잭션이 롤백되면 Job과 Step 상태가 모두 STARTED로 남는다")
    void recoveryRollbackRestoresJobAndStepStates() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);
        StepExecution step = previous.createStepExecution("calculateWeeklyScores");
        step.setStatus(BatchStatus.STARTED);
        jobRepository.add(step);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recovery.abandonAfterTerminationConfirmed(previous.getId());
            status.setRollbackOnly();
        });

        assertThat(List.of(jobExplorer.getJobExecution(previous.getId()).getStatus(),
                jobExplorer.getStepExecution(previous.getId(), step.getId()).getStatus()))
                .containsExactly(BatchStatus.STARTED, BatchStatus.STARTED);
    }

    @Test
    @DisplayName("완료된 Job은 종료 확인 복구로 바꿀 수 없다")
    void completedExecutionCannotBeAbandoned() throws Exception {
        JobExecution completed = launch(snapshot(1));

        assertThatThrownBy(() -> recovery.abandonAfterTerminationConfirmed(completed.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("종료 확인이 없는 복구 명령은 실행할 수 없다")
    void recoveryCommandRequiresTerminationConfirmation() {
        assertThatThrownBy(() -> new WeeklyRankingRecoveryRunner(recovery, 1L, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Job 자동 실행이 켜져 있으면 복구 명령을 실행할 수 없다")
    void recoveryCommandRequiresAutomaticJobLaunchDisabled() {
        assertThatThrownBy(() -> new WeeklyRankingRecoveryRunner(recovery, 1L, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("운영자가 종료를 확인한 복구 명령을 실행하면 새 작업이 허용된다")
    void recoveryCommandAllowsNewInstance() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);
        var command = new WeeklyRankingRecoveryRunner(recovery, previous.getId(), true, false);

        command.run(null);
        JobExecution next = launch(snapshot(2));

        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("복구 옵션으로 별도 애플리케이션을 시작하면 지정한 실행이 포기된다")
    void applicationStartsRecoveryOnlyWhenExplicitlyConfigured() throws Exception {
        JobExecution previous = jobRepository.createJobExecution(job.getName(), params(WEEK, snapshot(1)));
        previous.setStatus(BatchStatus.STARTED);
        jobRepository.update(previous);

        try (var context = new SpringApplicationBuilder(CommerceBatchApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=test",
                        "--spring.batch.job.name=weeklyRanking",
                        "--spring.batch.job.enabled=false",
                        "--spring.batch.jdbc.initialize-schema=never",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--weekly-ranking.recovery.execution-id=" + previous.getId(),
                        "--weekly-ranking.recovery.termination-confirmed=true")) {
            assertThat(jobExplorer.getJobExecution(previous.getId()).getStatus()).isEqualTo(BatchStatus.ABANDONED);
        }
    }
}
