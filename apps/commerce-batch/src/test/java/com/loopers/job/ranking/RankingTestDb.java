package com.loopers.job.ranking;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 랭킹 batch 테스트의 fixture 입력과, 애플리케이션 pool 이 아닌 새 연결·JobExplorer 로 하는 관찰. */
final class RankingTestDb {

    private RankingTestDb() {}

    static JdbcTemplate freshJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            System.getProperty("datasource.mysql-jpa.main.username"),
            System.getProperty("datasource.mysql-jpa.main.password")));
    }

    static void insertSource(JdbcTemplate jdbc, long orderId, long productId, long quantity, LocalDateTime confirmedAtUtc) {
        jdbc.update("INSERT INTO product_purchase_activity "
                + "(order_id, product_id, quantity, confirmed_at, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)",
            orderId, productId, quantity, confirmedAtUtc);
    }

    static long countBySnapshot(JdbcTemplate jdbc, String table, String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE snapshot_id = ?", Long.class, snapshotId);
    }

    static long countSourceOrders(JdbcTemplate jdbc, long... orderIds) {
        String in = String.join(",", Arrays.stream(orderIds).mapToObj(Long::toString).toList());
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id IN (" + in + ")", Long.class);
    }

    static Map<Long, BigInteger> stageScores(String snapshotId) {
        Map<Long, BigInteger> scores = new LinkedHashMap<>();
        freshJdbc().query("SELECT product_id, score FROM ranking_score_stage WHERE snapshot_id = ? ORDER BY product_id",
            (RowCallbackHandler) rs -> scores.put(rs.getLong("product_id"), rs.getBigDecimal("score").toBigIntegerExact()),
            snapshotId);
        return scores;
    }

    /** 순위순 "productId:score:rank". */
    static List<String> rankedResults(String snapshotId) {
        return freshJdbc().query("SELECT product_id, score, calculated_rank FROM ranking_result WHERE snapshot_id = ? "
                + "ORDER BY calculated_rank",
            (rs, rowNum) -> rs.getLong(1) + ":" + rs.getBigDecimal(2).toBigIntegerExact() + ":" + rs.getLong(3), snapshotId);
    }

    /** status·input_count·product_count·input_checksum·result_checksum. 행이 없으면 빈 목록. */
    static List<Object> manifest(String snapshotId) {
        List<List<Object>> rows = freshJdbc().query("SELECT status, input_count, product_count, input_checksum, "
                + "result_checksum FROM ranking_snapshot WHERE snapshot_id = ?",
            (rs, rowNum) -> Arrays.asList(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
            snapshotId);
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    /** 기간 pointer 의 "snapshotId@publishedAt". 없으면 null. */
    static String publication(LocalDate periodStart) {
        return freshJdbc().query("SELECT published_snapshot_id, published_at FROM ranking_publication WHERE period_start = ?",
                (rs, rowNum) -> rs.getString(1) + "@" + rs.getObject(2, LocalDateTime.class), periodStart)
            .stream().findFirst().orElse(null);
    }

    static String persistedJobStatus(long executionId) {
        return freshJdbc().queryForObject("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
            String.class, executionId);
    }

    /** JobExplorer 로 다시 읽은 이 실행의 Step 이름·상태. */
    static List<Map.Entry<String, BatchStatus>> persistedStepStatuses(JobExplorer explorer, JobExecution execution) {
        return explorer.getJobExecution(execution.getId()).getStepExecutions().stream()
            .sorted(Comparator.comparing(StepExecution::getId))
            .map(step -> Map.entry(step.getStepName(), step.getStatus()))
            .toList();
    }

    /** 메모리의 실행 객체가 아니라 JobExplorer 로 영속 StepExecution 을 다시 읽는다. */
    static StepExecution persistedStep(JobExplorer explorer, JobExecution execution, String stepName) {
        long stepExecutionId = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst().orElseThrow().getId();
        return explorer.getStepExecution(execution.getId(), stepExecutionId);
    }
}
