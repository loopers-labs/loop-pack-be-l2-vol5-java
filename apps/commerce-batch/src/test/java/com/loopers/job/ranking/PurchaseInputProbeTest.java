package com.loopers.job.ranking;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3 연결 예시: 고정한 snapshot(probe-1)에서 상품 7·8·9 를 chunk 2 로 읽어, writer 의 DB 쓰기와 reader checkpoint 가 같은 commit 에 들어가는지 본다.
 * 랭킹 정답 구현이 아니다. Boot 의 JDBC JobRepository·JobLauncher(동기)·primary DataSource 의 transactionManager 를 그대로 쓴다.
 * 첫 실행과 재실행 사이에 입력·확인 행·Batch metadata·MySQL 을 초기화하지 않는다. 테스트 전체 rollback 도 없다.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurchaseInputProbeTest {
    private static final String SNAPSHOT = "probe-1";

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private JobExplorer jobExplorer;

    Job probe(JobRepository repo, PlatformTransactionManager tx, DataSource ds, JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe").saveState(true)
            .dataSource(ds)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, n) -> rs.getLong("product_id"))
            .build();
        var jdbc = new JdbcTemplate(ds);
        var step = new StepBuilder("probeProducts", repo)
            .<Long, Long>chunk(2, tx)
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
        return new JobBuilder("purchaseInputProbe", repo).start(step).build();
    }

    private List<Long> probeRows(JdbcTemplate jdbc) {
        return jdbc.queryForList("select product_id from batch_restart_probe where snapshot_id = ? order by product_id", Long.class, SNAPSHOT);
    }

    @DisplayName("[§3] 첫 실행은 상품 9 직전에 FAILED 이고 7·8 과 read.count=2 가 commit 돼 있다. 같은 parameter 의 재실행은 같은 JobInstance 의 새 JobExecution 으로 9 만 써서 7·8·9 가 된다.")
    @Test
    void resumesFromCommittedReaderPosition() throws Exception {
        RankingTestDb db = new RankingTestDb(dataSource);
        db.prepareSchema();
        db.section6Fixture();
        JdbcTemplate jdbc = db.jdbc();
        jdbc.execute("create table if not exists batch_restart_probe (snapshot_id varchar(64) not null, product_id bigint not null, "
            + "primary key (snapshot_id, product_id)) engine=InnoDB");
        jdbc.update("insert into ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at) "
            + "select ?, order_id, product_id, quantity, confirmed_at from product_purchase_activity "
            + "where confirmed_at >= '2026-08-17 00:00:00' and confirmed_at < '2026-08-24 00:00:00'", SNAPSHOT);
        assertThat(probeRows(jdbc)).isEmpty();
        assertThat(db.inputs(SNAPSHOT)).hasSize(4);

        var params = new JobParametersBuilder().addString("snapshotId", SNAPSHOT, true).toJobParameters();
        JobExecution first = jobLauncher.run(probe(jobRepository, transactionManager, dataSource, params, true), params);

        StepExecution firstStep = first.getStepExecutions().iterator().next();
        StepExecution persisted = jobExplorer.getStepExecution(first.getId(), firstStep.getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persisted.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persisted.getExecutionContext().getInt("purchaseProductProbe.read.count")).isEqualTo(2);
        assertThat(persisted.getCommitCount()).isEqualTo(1);
        assertThat(probeRows(jdbc)).containsExactly(7L, 8L);

        JobExecution second = jobLauncher.run(probe(jobRepository, transactionManager, dataSource, params, false), params);

        StepExecution secondStep = jobExplorer.getStepExecution(second.getId(), second.getStepExecutions().iterator().next().getId());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(secondStep.getReadCount()).isEqualTo(1);
        assertThat(secondStep.getWriteCount()).isEqualTo(1);
        // 완료 뒤 read.count 는 item 3 + 끝을 확인한 마지막 read(null) 1 = 4 (AbstractItemCountingItemStreamItemReader 는 null read 도 센다)
        assertThat(secondStep.getExecutionContext().getInt("purchaseProductProbe.read.count")).isEqualTo(4);
        assertThat(probeRows(jdbc)).containsExactly(7L, 8L, 9L);
    }
}
