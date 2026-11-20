package com.loopers.batch.job.ranking;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

class WeeklyRankingJobFixture {

    static final LocalDate WEEK = LocalDate.of(2026, 8, 17);

    private static final List<String> TABLES = List.of(
        "purchase_activity", "ranking_snapshot", "ranking_snapshot_input",
        "ranking_score_stage", "weekly_ranking", "weekly_ranking_publication"
    );

    private final DataSource dataSource;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JobRepositoryTestUtils metadata;
    private final JdbcTemplate jdbc;
    private final List<Long> executionIds = new ArrayList<>();

    WeeklyRankingJobFixture(
        DataSource dataSource, JobLauncher jobLauncher, JobExplorer jobExplorer, JobRepository jobRepository
    ) {
        this.dataSource = dataSource;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.metadata = new JobRepositoryTestUtils(jobRepository);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    static String snapshotId(int revision) {
        return "weekly-20260817-r" + revision;
    }

    static JobParameters params(int revision) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLong("revision", (long) revision)
            .addString("snapshotId", snapshotId(revision))
            .toJobParameters();
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    void emptyTables() {
        TABLES.forEach(table -> jdbc.execute("truncate table " + table));
    }

    void removeExecutions() {
        metadata.removeJobExecutions(executionIds.stream().map(jobExplorer::getJobExecution).toList());
        executionIds.clear();
    }

    void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update(
            "insert into purchase_activity "
                + "(created_at, updated_at, order_id, product_id, quantity, confirmed_at) "
                + "values (now(6), now(6), ?, ?, ?, ?)",
            orderId, productId, quantity, confirmedAtUtc
        );
    }

    void insertWeekWithProductsSevenEightNine() {
        insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(42L, 8L, 1, LocalDateTime.parse("2026-08-17T01:00:00"));
        insertActivity(43L, 7L, 3, LocalDateTime.parse("2026-08-18T01:00:00"));
        insertActivity(44L, 9L, 5, LocalDateTime.parse("2026-08-19T01:00:00"));
    }

    long launch(Job job, int revision) throws Exception {
        return launch(job, params(revision));
    }

    long launch(Job job, JobParameters params) throws Exception {
        JobExecution execution = jobLauncher.run(job, params);
        executionIds.add(execution.getId());
        return execution.getId();
    }

    long launchWhileHolding(String uncommittedInsert, String snapshotId, Job job, JobParameters params)
        throws Exception {
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement(uncommittedInsert)) {
                hold.setString(1, snapshotId);
                hold.executeUpdate();
            }
            long executionId = launch(job, params);
            blocker.rollback();
            return executionId;
        }
    }

    void forget(long executionId) {
        metadata.removeJobExecutions(List.of(jobExplorer.getJobExecution(executionId)));
        executionIds.remove(executionId);
    }

    JobExecution execution(long executionId) {
        return jobExplorer.getJobExecution(executionId);
    }

    BatchStatus statusOf(long executionId) {
        return execution(executionId).getStatus();
    }

    StepExecution stepNamed(long executionId, String stepName) {
        StepExecution step = execution(executionId).getStepExecutions().stream()
            .filter(candidate -> candidate.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(executionId, step.getId());
    }

    long countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    Map<String, Object> snapshotInfo(String snapshotId) {
        return jdbc.queryForMap(
            "select period_start, revision, status, frozen_at, input_count, input_checksum "
                + "from ranking_snapshot where snapshot_id = ?",
            snapshotId
        );
    }

    List<String> frozenInputs(String snapshotId) {
        return jdbc.query(
            "select order_id, product_id, quantity from ranking_snapshot_input "
                + "where snapshot_id = ? order by order_id, product_id",
            (rs, rowNum) -> rs.getLong("order_id") + ":" + rs.getLong("product_id") + ":" + rs.getInt("quantity"),
            snapshotId
        );
    }

    List<String> stagedScores(String snapshotId) {
        return jdbc.query(
            "select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id",
            (rs, rowNum) -> rs.getLong("product_id") + ":" + rs.getLong("score"),
            snapshotId
        );
    }

    List<String> ranking(String snapshotId) {
        return jdbc.query(
            "select rank_no, product_id, score from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, rowNum) -> rs.getInt("rank_no") + ":" + rs.getLong("product_id") + ":" + rs.getLong("score"),
            snapshotId
        );
    }

    Map<String, Object> publication() {
        return jdbc.queryForMap(
            "select snapshot_id, revision, published_at, frozen_at from weekly_ranking_publication "
                + "where period_start = ?",
            WEEK
        );
    }
}
