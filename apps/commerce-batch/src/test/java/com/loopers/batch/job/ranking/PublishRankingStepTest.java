package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.chunk-size=2"
})
@Sql("/sql/purchase-activity.sql")
class PublishRankingStepTest {

    private static final String SNAPSHOT_ID = "weekly-20260817-r1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    private JdbcTemplate jdbc;

    private final List<Long> executionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table purchase_activity");
        jdbc.execute("truncate table ranking_snapshot");
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute("truncate table ranking_score_stage");
        jdbc.execute("truncate table weekly_ranking");
        jdbc.execute("truncate table weekly_ranking_publication");

        insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        insertActivity(44L, 9L, 5, LocalDateTime.parse("2026-08-19T01:00:00"));
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            executionIds.stream().map(jobExplorer::getJobExecution).toList()
        );
    }

    @DisplayName("PBL-001, PBL-002, RNK-004: 점수 내림차순, 같으면 상품 오름차순으로 순위를 매겨 완성 ranking 에 쓰고 기간의 공개 pointer 를 그 snapshot 으로 둔다.")
    @Test
    void publishesRankedResultAndPointsPeriodToSnapshot() throws Exception {
        long executionId = launch();

        Map<String, Object> publication = jdbc.queryForMap(
            "select snapshot_id, revision from weekly_ranking_publication where period_start = '2026-08-17'"
        );
        assertAll(
            () -> assertThat(statusOf(executionId)).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(publishedRanking()).containsExactly("1:7:5", "2:9:5", "3:8:1"),
            () -> assertThat(publication.get("snapshot_id")).isEqualTo(SNAPSHOT_ID),
            () -> assertThat(publication.get("revision")).isEqualTo(1)
        );
    }

    private long launch() throws Exception {
        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters());
        executionIds.add(execution.getId());
        return execution.getId();
    }

    private BatchStatus statusOf(long executionId) {
        return jobExplorer.getJobExecution(executionId).getStatus();
    }

    private List<String> publishedRanking() {
        return jdbc.query(
            "select rank_no, product_id, score from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, rowNum) -> rs.getInt("rank_no") + ":" + rs.getLong("product_id") + ":" + rs.getLong("score"),
            SNAPSHOT_ID
        );
    }

    private void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), ?, ?, ?, ?)",
            orderId, productId, quantity, confirmedAtUtc
        );
    }
}
