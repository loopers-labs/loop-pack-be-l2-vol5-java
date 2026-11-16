package com.loopers.job.ranking.probe;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

// T2-A: 결과 쓰기가 step 트랜잭션에 참여하는 설정인지 본다. DataSource가 하나이고, step TM이 다루는 DataSource가 writer의 것과 같으며,
// writer 안에서 실제 트랜잭션이 열려 있고 그 DataSource의 연결이 트랜잭션에 묶여 있어야 위치 저장과 결과 쓰기가 한 commit이 된다.
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class RankingProbeTransactionConfigTest {
    @Autowired private ApplicationContext context;
    @Autowired private JobLauncher jobLauncher;
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
    @DisplayName("T2-A: DataSource 빈은 하나이고 step TM이 다루는 DataSource가 writer의 DataSource와 같으며, writer 안에서 실제 트랜잭션이 열려 있고 연결이 그 트랜잭션에 묶여 있다")
    void writerJoinsStepTransaction() throws Exception {
        Map<String, DataSource> dataSources = context.getBeansOfType(DataSource.class);
        AtomicBoolean transactionActive = new AtomicBoolean();
        AtomicBoolean connectionBound = new AtomicBoolean();
        AtomicBoolean connectionTransactional = new AtomicBoolean();
        AtomicReference<String> checked = new AtomicReference<String>();

        JobExecution execution = jobLauncher.run(fixture.job(snapshotId, fixture.insertWriter(snapshotId, null, chunk -> {
            if (checked.get() != null) {
                return;
            }
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            connectionBound.set(TransactionSynchronizationManager.hasResource(dataSource));
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                connectionTransactional.set(DataSourceUtils.isConnectionTransactional(connection, dataSource));
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            checked.set("first chunk");
        }), null), new JobParametersBuilder().addString("snapshotId", snapshotId).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.values().iterator().next()).isSameAs(dataSource);
        assertThat(managedDataSource(transactionManager)).isSameAs(dataSource);
        assertThat(checked.get()).isNotNull();
        assertThat(transactionActive.get()).isTrue();
        assertThat(connectionBound.get()).isTrue();
        assertThat(connectionTransactional.get()).isTrue();
    }

    private static DataSource managedDataSource(PlatformTransactionManager manager) {
        if (manager instanceof JpaTransactionManager jpa) {
            return jpa.getDataSource();
        }
        if (manager instanceof DataSourceTransactionManager jdbc) {
            return jdbc.getDataSource();
        }
        throw new AssertionError("DataSource를 다루지 않는 TM: " + manager.getClass().getName());
    }
}
