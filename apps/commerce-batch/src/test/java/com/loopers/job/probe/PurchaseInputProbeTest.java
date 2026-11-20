package com.loopers.job.probe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9주차 랭킹 job을 만들기 전에, 실제 MySQL·JDBC JobRepository·결과 writer가 같은 DB 트랜잭션으로 묶여
 * 실패한 chunk 이후부터 같은 JobInstance로 이어지는지 확인하는 연결 실험이다 (과제 §3 "세 상품으로 DB chunk 재시작 연결하기").
 * 제품 코드가 아니며, 테이블도 이 테스트만 쓴다. 랭킹 점수·순위·공개의 정답 구현은 아니다.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurchaseInputProbeTest {

    private static final String SNAPSHOT_ID = "probe-1";
    private static final String STEP_NAME = "probeProducts";

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    private JdbcTemplate jdbc;

    @BeforeEach
    void freezeProbeInput() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS probe_snapshot_input");
        jdbc.execute("DROP TABLE IF EXISTS batch_restart_probe");
        jdbc.execute("""
            CREATE TABLE probe_snapshot_input (
              snapshot_id VARCHAR(64) NOT NULL,
              order_id BIGINT NOT NULL,
              product_id BIGINT NOT NULL,
              quantity INT NOT NULL,
              PRIMARY KEY (snapshot_id, order_id, product_id)
            ) ENGINE=InnoDB""");
        jdbc.execute("""
            CREATE TABLE batch_restart_probe (
              snapshot_id VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB""");
        // 과제 §6의 구매 활동 4행을 probe-1로 고정한다. reader item은 서로 다른 상품 7·8·9의 3개다.
        jdbc.batchUpdate(
            "insert into probe_snapshot_input (snapshot_id, order_id, product_id, quantity) values (?, ?, ?, ?)",
            List.of(
                new Object[]{SNAPSHOT_ID, 42L, 7L, 2},
                new Object[]{SNAPSHOT_ID, 42L, 8L, 1},
                new Object[]{SNAPSHOT_ID, 43L, 7L, 3},
                new Object[]{SNAPSHOT_ID, 44L, 9L, 5}
            )
        );
    }

    @DisplayName("세 번째 상품 직전에 실패한 실행을 같은 parameter로 다시 실행하면, 같은 JobInstance의 새 실행이 커밋된 위치 다음 상품부터 이어 간다.")
    @Test
    void restartsFromLastCommittedChunk_inSameJobInstance() throws Exception {
        // 실패 스위치는 식별 parameter가 아니다. run.id·현재 시각도 넣지 않는다.
        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", SNAPSHOT_ID, true)
            .toJobParameters();

        // 1차 실행: 상품 9를 처리하기 직전에 실패한다.
        JobExecution first = jobLauncher.run(probe(params, true), params);

        StepExecution firstStep = stepOf(first);
        // 메모리 값이 아니라 JobRepository에 영속된 ExecutionContext를 새로 읽는다.
        StepExecution persistedFirstStep = jobExplorer.getStepExecution(first.getId(), firstStep.getId());

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(probeRows()).containsExactly(7L, 8L);
        assertThat(persistedFirstStep).isNotNull();
        assertThat(persistedFirstStep.getExecutionContext().getInt("purchaseProductProbe.read.count")).isEqualTo(2);
        assertThat(persistedFirstStep.getCommitCount()).isEqualTo(1);

        // 2차 실행: 입력·확인 행·Batch metadata를 지우지 않고, 새 reader·step·job 객체를 같은 이름·같은 parameter로 만든다.
        JobExecution second = jobLauncher.run(probe(params, false), params);

        StepExecution secondStep = stepOf(second);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // 앞의 두 상품을 다시 쓰면 PK 충돌로 실패했을 것이다. writer는 상품 9 한 건만 추가한다.
        assertThat(secondStep.getWriteCount()).isEqualTo(1);
        assertThat(probeRows()).containsExactly(7L, 8L, 9L);
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe")
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from probe_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, n) -> rs.getLong("product_id"))
            .build();
        var step = new StepBuilder(STEP_NAME, jobRepository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader)
            .processor((ItemProcessor<Long, Long>) id -> {
                if (failOnNine && id == 9L) {
                    throw new IllegalStateException("before third product");
                }
                return id;
            })
            .writer(items -> {
                for (Long id : items) {
                    jdbc.update("insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)", snapshot, id);
                }
            })
            .build();
        return new JobBuilder("purchaseInputProbe", jobRepository).start(step).build();
    }

    private StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().stream()
            .filter(it -> it.getStepName().equals(STEP_NAME))
            .findFirst()
            .orElseThrow();
    }

    private List<Long> probeRows() {
        return jdbc.queryForList(
            "select product_id from batch_restart_probe where snapshot_id = ? order by product_id", Long.class, SNAPSHOT_ID);
    }
}
