package com.loopers.ranking.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservedMySql;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class OrderPartitionJobIdentityTest {
    @Test
    @DisplayName("명칭 전환 전 실행은 이전 식별자로 보존되고 전환 후 담당자 변경은 같은 JobInstance를 재시작한다")
    void preservesHistoryAndRestartsTheNewIdentity() throws Exception {
        try (var database = new PreservedMySql()) {
            try (var connection = database.connect()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/001-batch-metadata-mysql.sql"));
            }
            var source = new DriverManagerDataSource(database.jdbcUrl(), "application", "application");
            var factory = new JobRepositoryFactoryBean();
            factory.setDatabaseType("MYSQL");
            factory.setDataSource(source);
            factory.setTransactionManager(new DataSourceTransactionManager(source));
            factory.afterPropertiesSet();
            var jobs = factory.getObject();

            var oldKey = parameters("shardId", "node-a", 1);
            var old = jobs.createJobExecution("weeklyRankingShard", oldKey);
            markFailed(old);
            jobs.update(old);
            var newKey = parameters("orderPartitionId", "node-a", 1);
            assertThat(jobs.getLastJobExecution("weeklyRankingOrderPartition", newKey)).isNull();
            assertThat(jobs.getLastJobExecution("weeklyRankingOrderPartition", oldKey)).isNull();
            assertThat(jobs.getLastJobExecution("weeklyRankingShard", newKey)).isNull();

            var first = jobs.createJobExecution("weeklyRankingOrderPartition", newKey);
            assertThat(first.getJobInstance().getId()).isNotEqualTo(old.getJobInstance().getId());
            markFailed(first);
            jobs.update(first);
            var restarted = jobs.createJobExecution("weeklyRankingOrderPartition", parameters("orderPartitionId", "node-b", 2));
            assertThat(restarted.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
            assertThat(restarted.getId()).isNotEqualTo(first.getId());
            assertThat(jobs.getLastJobExecution("weeklyRankingShard", oldKey).getId()).isEqualTo(old.getId());
        }
    }

    private static JobParameters parameters(String partitionKey, String owner, long generation) {
        return new JobParametersBuilder().addString("snapshotId", "00000000-0000-0000-0000-000000000099")
            .addLong(partitionKey, 1L).addString("ownerId", owner, false).addLong("generation", generation, false)
            .toJobParameters();
    }

    private static void markFailed(JobExecution execution) {
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(ExitStatus.FAILED);
        execution.setEndTime(LocalDateTime.now());
    }
}
