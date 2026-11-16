package com.loopers.job.ranking.probe;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

// T2-B: 결과 쓰기와 위치 저장이 한 commit인지 장애 주입으로 본다. step에 등록한 ItemStream의 update()는 chunk 트랜잭션 안에서
// 위치 저장 직전에 불린다. writer가 실제로 쓴 뒤의 update()에서만 실패시켜, 그 chunk의 결과 행과 위치가 함께 rollback되는지 본다.
// update()는 step 시작 때 chunk 밖에서도 한 번 불리므로, writer가 쓰기 전 호출에서는 실패하지 않는다.
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class RankingProbeTransactionFailureTest {
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
    @DisplayName("T2-B: 첫 chunk를 쓴 뒤 위치 저장 직전 update()에서 실패하면 Job은 FAILED이고 그 chunk의 결과 행이 남지 않으며 영속된 읽은 위치도 앞서지 않는다")
    void writeAndPositionRollBackTogether() throws Exception {
        AtomicBoolean written = new AtomicBoolean();
        ItemWriter<Long> insert = fixture.insertWriter(snapshotId, null, chunk -> { });
        ItemWriter<Long> writer = chunk -> {
            insert.write(chunk);
            written.set(true);
        };
        FailAfterWriteStream stream = new FailAfterWriteStream(written);

        JobExecution execution = jobLauncher.run(fixture.job(snapshotId, writer, stream),
            new JobParametersBuilder().addString("snapshotId", snapshotId).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(causeMessages(execution)).contains("주입된 위치 저장 직전 실패");
        assertThat(fixture.resultRows(snapshotId)).isEmpty();
        StepExecution step = jobExplorer.getStepExecution(execution.getId(), execution.getStepExecutions().iterator().next().getId());
        assertThat(step.getExecutionContext().getInt(RankingProbeFixture.READ_COUNT_KEY, 0)).isZero();
        assertThat(step.getCommitCount()).isZero();
        assertThat(stream.calls()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(stream.calls().get(0)).isEqualTo("update#1 written=false tx=false");
        // 몇 번째 호출인지는 Spring Batch 내부 순서에 맡기고, 실패한 호출이 writer가 쓴 뒤의 첫 트랜잭션 안 호출인지만 본다.
        assertThat(stream.calls()).filteredOn(call -> call.contains("written=true tx=true")).first().asString().endsWith("실패");
        assertThat(stream.calls()).filteredOn(call -> call.endsWith("실패")).hasSize(1);
    }

    private static String causeMessages(JobExecution execution) {
        StringBuilder messages = new StringBuilder();
        for (Throwable failure : execution.getAllFailureExceptions()) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private static class FailAfterWriteStream implements ItemStream {
        private final AtomicBoolean written;
        private final List<String> calls = new CopyOnWriteArrayList<String>();

        FailAfterWriteStream(AtomicBoolean written) {
            this.written = written;
        }

        @Override
        public void update(ExecutionContext executionContext) {
            String call = "update#" + (calls.size() + 1) + " written=" + written.get() + " tx="
                + TransactionSynchronizationManager.isActualTransactionActive();
            if (written.get()) {
                calls.add(call + " 실패");
                throw new IllegalStateException("주입된 위치 저장 직전 실패");
            }
            calls.add(call);
        }

        List<String> calls() {
            return List.copyOf(calls);
        }
    }
}
