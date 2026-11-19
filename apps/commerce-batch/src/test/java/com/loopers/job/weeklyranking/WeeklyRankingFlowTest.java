package com.loopers.job.weeklyranking;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;

import static com.loopers.job.weeklyranking.PurchaseActivityRows.insert;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고정 입력·revision·공개 흐름. JobInstance 가 시나리오끼리 섞이지 않도록 시나리오마다 다른 주를 쓰고,
 * 한 시나리오 안의 실행 사이에는 업무 DB·Batch metadata 를 지우지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
class WeeklyRankingFlowTest {

    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("구간 시작은 포함하고 끝은 제외하며, 같은 주문의 여러 품목을 모두 고정한다.")
    @Test
    void freezesBoundaryAndAllItemsOfSameOrder() throws Exception {
        // arrange
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 40, 1, 1, "2026-09-06T23:59:59.999999Z");
        insert(jdbc, 41, 7, 2, "2026-09-07T00:00:00Z");
        insert(jdbc, 41, 8, 1, "2026-09-07T00:00:00Z");
        insert(jdbc, 45, 9, 1, "2026-09-13T23:59:59.999999Z");
        insert(jdbc, 46, 9, 1, "2026-09-14T00:00:00Z");

        // act
        JobExecution execution = jobLauncher.run(job, params("2026-09-07", 1));

        // assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList("""
            SELECT order_id, product_id FROM weekly_ranking_snapshot_input
            WHERE snapshot_id = 'weekly-20260907-r1' ORDER BY order_id, product_id""")).containsExactly(
            Map.of("order_id", 41L, "product_id", 7L),
            Map.of("order_id", 41L, "product_id", 8L),
            Map.of("order_id", 45L, "product_id", 9L));
        assertThat(jdbc.queryForMap("""
            SELECT input_count, product_count, status FROM weekly_ranking_snapshot
            WHERE snapshot_id = 'weekly-20260907-r1'""")).isEqualTo(
            Map.of("input_count", 3, "product_count", 3, "status", "FROZEN"));
    }

    private static JobParameters params(String periodStart, long revision) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.parse(periodStart))
            .addLong("revision", revision)
            .toJobParameters();
    }

}
