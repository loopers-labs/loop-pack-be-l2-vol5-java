package com.loopers.job.ranking.probe;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

// T1: 같은 JobInstance 재시작이 영속된 읽은 위치에서 이어 가는지 본다. 실제 MySQL·실제 JobRepository·step TM을 쓴다.
// @SpringBatchTest(step scope가 테스트 범위에 묶임)·@Transactional(자동 rollback)·메모리 repository는 쓰지 않는다.
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class RankingRestartProbeTest {
    @Autowired private JobLauncher jobLauncher;
    @Autowired private JobExplorer jobExplorer;
    @Autowired private JobRepository jobRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private RankingProbeFixture fixture;
    private String snapshotId;

    @BeforeEach
    void prepare() {
        fixture = new RankingProbeFixture(jobRepository, transactionManager, dataSource);
        fixture.createTables();
        snapshotId = "probe-" + UUID.randomUUID();
        fixture.insertInput(snapshotId);
    }

    @AfterEach
    void clean() {
        fixture.dropTables();
    }

    @Test
    @DisplayName("T1: 상품 9에서 실패한 뒤 같은 파라미터로 다시 실행하면 같은 JobInstance의 새 실행이 읽은 위치 2 뒤의 9만 읽고 써서 결과가 7·8·9가 된다")
    void restartResumesFromPersistedReadPosition() throws Exception {
        JobParameters parameters = new JobParametersBuilder().addString("snapshotId", snapshotId).toJobParameters();

        JobExecution first = jobLauncher.run(fixture.job(snapshotId, fixture.insertWriter(snapshotId, 9L, chunk -> { }), null), parameters);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(fixture.resultRows(snapshotId)).containsExactly(7L, 8L);
        StepExecution firstStep = persistedStep(first);
        assertThat(firstStep.getExecutionContext().getInt(RankingProbeFixture.READ_COUNT_KEY)).isEqualTo(2);
        assertThat(firstStep.getCommitCount()).isEqualTo(1);
        assertThat(firstStep.getRollbackCount()).isGreaterThanOrEqualTo(1);

        JobExecution second = jobLauncher.run(fixture.job(snapshotId, fixture.insertWriter(snapshotId, null, chunk -> { }), null), parameters);

        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(fixture.resultRows(snapshotId)).containsExactly(7L, 8L, 9L);
        StepExecution secondStep = persistedStep(second);
        assertThat(secondStep.getReadCount()).isEqualTo(1);
        assertThat(secondStep.getWriteCount()).isEqualTo(1);
        JobInstance instance = jobExplorer.getJobInstance(first.getJobInstance().getInstanceId());
        List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
        assertThat(executions).hasSize(2);
    }

    // 메모리의 실행 객체가 아니라 JobRepository에 저장된 값을 읽는다.
    private StepExecution persistedStep(JobExecution execution) {
        StepExecution inMemory = execution.getStepExecutions().iterator().next();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }
}
