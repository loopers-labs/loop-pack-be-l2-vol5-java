package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 7절 #8: 같은 JobInstance 의 동시 실행은 JobRepository 가 거절한다. 스레드 경쟁(flaky) 대신 latch 로 "실행 중" 상태를 결정적으로 만든다.
 * 운영 JobLauncher 설정은 바꾸지 않고, 비동기가 필요한 1차 실행에만 테스트 로컬 TaskExecutorJobLauncher 를 쓴다.
 */
@Import(RankingTestConfig.class)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class WeeklyRankingConcurrentLaunchTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private JobExplorer jobExplorer;

    @DisplayName("실행 중인 JobInstance 에 같은 파라미터로 다시 launch 하면 JobExecutionAlreadyRunningException 이고, 첫 실행은 끝까지 COMPLETED 다.")
    @Test
    void sameInstance_secondLaunchIsRejected() throws Exception {
        // arrange — latch 에서 멈추는 probe Job
        CountDownLatch latch = new CountDownLatch(1);
        Step latchStep = new StepBuilder("latchStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                latch.await();
                return RepeatStatus.FINISHED;
            }, transactionManager)
            .build();
        Job probe = new JobBuilder("concurrentLaunchProbe", jobRepository).start(latchStep).build();
        JobParameters params = new JobParametersBuilder().addString("snapshotId", "concurrent-probe-1", true).toJobParameters();

        TaskExecutorJobLauncher asyncLauncher = new TaskExecutorJobLauncher();
        asyncLauncher.setJobRepository(jobRepository);
        asyncLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("concurrent-probe-"));
        asyncLauncher.afterPropertiesSet();

        // act 1 — 비동기 1차 실행이 STARTED 가 될 때까지 대기
        JobExecution first = asyncLauncher.run(probe, params);
        awaitStatus(first.getId(), status -> status == BatchStatus.STARTED);

        // act 2 / assert — 같은 파라미터 동기 2차 launch 는 거절
        assertThatThrownBy(() -> jobLauncher.run(probe, params)).isInstanceOf(JobExecutionAlreadyRunningException.class);

        // act 3 / assert — latch 해제 후 1차 COMPLETED
        latch.countDown();
        awaitStatus(first.getId(), status -> status == BatchStatus.COMPLETED);
        assertThat(jobExplorer.getJobExecution(first.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private void awaitStatus(Long executionId, Predicate<BatchStatus> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            BatchStatus status = jobExplorer.getJobExecution(executionId).getStatus();
            if (condition.test(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("executionId=" + executionId + " 가 기대 상태에 도달하지 않음: "
            + jobExplorer.getJobExecution(executionId).getStatus());
    }
}
