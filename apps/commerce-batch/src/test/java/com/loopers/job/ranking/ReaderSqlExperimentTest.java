package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static com.loopers.job.ranking.PurchaseActivityFixture.purchase;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 weeklyRanking의 상품 ID 페이지, 상품별 합계 조회와 stage INSERT를 확인한다. */
@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "commerce.ranking.weekly.chunk-size=2"
})
class ReaderSqlExperimentTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    @Test
    void captureReaderSql() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"), "root", "test"));
        new PurchaseActivityFixture(jdbc).insert(
            purchase(901, 7, 2, "2027-09-06T01:00:00"),
            purchase(901, 8, 1, "2027-09-06T01:00:00"),
            purchase(902, 7, 3, "2027-09-07T01:00:00"),
            purchase(903, 9, 5, "2027-09-08T01:00:00"),
            purchase(904, 11, 4, "2027-09-09T01:00:00"));

        root.execute("TRUNCATE TABLE mysql.general_log");
        root.execute("SET GLOBAL log_output = 'TABLE'");
        root.execute("SET GLOBAL general_log = 'ON'");
        try {
            var execution = jobLauncher.run(job, new JobParametersBuilder()
                .addString("periodStart", "2027-09-06", true)
                .addString("snapshotId", "weekly-20270906-r1", true)
                .toJobParameters());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        } finally {
            root.execute("SET GLOBAL general_log = 'OFF'");
        }

        var statements = root.queryForList("""
            SELECT CONVERT(argument USING utf8mb4) AS sql_text FROM mysql.general_log
            WHERE command_type IN ('Query', 'Execute')
              AND (CONVERT(argument USING utf8mb4) LIKE 'SELECT DISTINCT product_id%'
                OR CONVERT(argument USING utf8mb4) LIKE 'SELECT COALESCE(SUM(quantity), 0) FROM ranking_snapshot_input%AND product_id = %'
                OR CONVERT(argument USING utf8mb4) LIKE 'INSERT INTO ranking_score_stage%')
            ORDER BY event_time
            """, String.class);
        statements.forEach(sql -> System.out.println("[reader-sql] " + sql.replaceAll("\\s+", " ")));
        var pages = statements.stream().filter(sql -> sql.startsWith("SELECT DISTINCT product_id")).toList();
        assertThat(pages).hasSize(3); // 7·8, 9·11, 다음 페이지가 비었는지 확인
        assertThat(pages).allSatisfy(sql -> assertThat(sql)
            .contains("FORCE INDEX (idx_ranking_snapshot_input_product)", "ORDER BY product_id"));
        assertThat(pages.get(1)).containsPattern("product_id\\s*>\\s*8");
        assertThat(pages.get(2)).containsPattern("product_id\\s*>\\s*11");

        var totals = statements.stream().filter(sql -> sql.startsWith("SELECT COALESCE(SUM(quantity)")).toList();
        assertThat(totals).hasSize(4); // 상품 7의 구매가 두 행이어도 합계 조회는 한 번
        assertThat(totals.get(0)).containsPattern("product_id\\s*=\\s*7\\b");
        assertThat(totals.get(1)).containsPattern("product_id\\s*=\\s*8\\b");
        assertThat(totals.get(2)).containsPattern("product_id\\s*=\\s*9\\b");
        assertThat(totals.get(3)).containsPattern("product_id\\s*=\\s*11\\b");

        var writes = statements.stream().filter(sql -> sql.startsWith("INSERT INTO ranking_score_stage")).toList();
        assertThat(writes).isNotEmpty().allSatisfy(sql -> assertThat(sql).doesNotContain("ON DUPLICATE KEY"));
    }
}
