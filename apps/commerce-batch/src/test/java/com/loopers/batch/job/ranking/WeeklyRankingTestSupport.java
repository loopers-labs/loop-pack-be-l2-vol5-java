package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 랭킹 테스트의 공통 준비. DB 시각은 UTC 날짜·시각이다.
 */
public final class WeeklyRankingTestSupport {

    public static final String PERIOD_START = "2026-08-17";
    public static final String RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    public static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    // 순위, 상품 ID, 점수
    public static final List<List<Long>> EXPECTED_RANKING = List.of(
        List.of(1L, 7L, 5L), List.of(2L, 9L, 5L), List.of(3L, 8L, 1L));

    private WeeklyRankingTestSupport() {
    }

    public static JobParameters parameters(String periodStart, String revision) {
        return new JobParametersBuilder()
            .addString(WeeklyRankingKey.PERIOD_START, periodStart)
            .addString(WeeklyRankingKey.REVISION, revision)
            .toJobParameters();
    }

    // 검증 기록의 fixture: 주문 42에 상품 7·8, 주문 43에 상품 7, 주문 44에 상품 9
    public static void insertFixture(JdbcTemplate jdbc) {
        insertActivity(jdbc, 42, 7, 2, "2026-08-17T01:00:00");
        insertActivity(jdbc, 42, 8, 1, "2026-08-17T01:00:00");
        insertActivity(jdbc, 43, 7, 3, "2026-08-18T01:00:00");
        insertActivity(jdbc, 44, 9, 5, "2026-08-19T01:00:00");
    }

    public static void insertActivity(JdbcTemplate jdbc, long orderId, long productId, int quantity, String confirmedAtUtc) {
        LocalDateTime createdAt = LocalDateTime.parse("2026-08-20T00:00:00");
        jdbc.update("""
            INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)""", orderId, productId, quantity, LocalDateTime.parse(confirmedAtUtc), createdAt, createdAt);
    }

    public static void softDeleteActivity(JdbcTemplate jdbc, long orderId, long productId) {
        jdbc.update("UPDATE product_purchase_activity SET deleted_at = ? WHERE order_id = ? AND product_id = ?",
            LocalDateTime.parse("2026-08-21T00:00:00"), orderId, productId);
    }

    /**
     * Tasklet Step과 같은 모양으로 실행한다. 그 Step의 격리 수준으로 연 트랜잭션 하나 안에서 tasklet을 한 번 부른다.
     * 점수 고정 Step은 READ_COMMITTED, 공개 Step은 기본 격리 수준이다.
     */
    public static void runTasklet(Tasklet tasklet, PlatformTransactionManager transactionManager, int isolationLevel,
                                  String periodStart, String revision, long jobExecutionId) {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
            "weeklyRanking", 1L, jobExecutionId, parameters(periodStart, revision));
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(jobExecution, "tasklet", 1L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolationLevel);
        transaction.executeWithoutResult(status -> {
            try {
                tasklet.execute(stepExecution.createStepContribution(), new ChunkContext(new StepContext(stepExecution)));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // 순위, 상품 ID, 점수를 순위 순으로 읽는다.
    public static List<List<Long>> ranking(JdbcTemplate jdbc, String periodStart, int revision) {
        return jdbc.query("""
                SELECT rank_no, product_id, score FROM weekly_ranking_scores
                WHERE period_start = ? AND revision = ? ORDER BY rank_no""",
            (result, rowNumber) -> List.of(result.getLong(1), result.getLong(2), result.getLong(3)),
            LocalDate.parse(periodStart), revision);
    }

    // 상품 ID, 점수, 순위. 순위를 매기기 전이면 순위는 null이다.
    public static List<List<Long>> scores(JdbcTemplate jdbc, String periodStart, int revision) {
        return jdbc.query("""
                SELECT product_id, score, rank_no FROM weekly_ranking_scores
                WHERE period_start = ? AND revision = ? ORDER BY product_id""",
            (result, rowNumber) -> Arrays.asList(
                result.getLong(1), result.getLong(2), result.getObject(3, Long.class)),
            LocalDate.parse(periodStart), revision);
    }

    public static Map<String, Object> revisionRow(JdbcTemplate jdbc, String periodStart, int revision) {
        return jdbc.queryForMap("""
            SELECT status, result_checksum, job_execution_id, cancelled_at, cancel_reason
            FROM weekly_ranking_revisions WHERE period_start = ? AND revision = ?""", LocalDate.parse(periodStart), revision);
    }

    public static LocalDateTime calculatedAt(JdbcTemplate jdbc, String periodStart, int revision) {
        return jdbc.queryForObject(
            "SELECT calculated_at FROM weekly_ranking_revisions WHERE period_start = ? AND revision = ?",
            (result, rowNumber) -> result.getObject(1, LocalDateTime.class), LocalDate.parse(periodStart), revision);
    }

    public static void setStatus(JdbcTemplate jdbc, String periodStart, int revision, String status) {
        jdbc.update("UPDATE weekly_ranking_revisions SET status = ? WHERE period_start = ? AND revision = ?",
            status, LocalDate.parse(periodStart), revision);
    }

    public static void insertRevision(JdbcTemplate jdbc, String periodStart, int revision, String status) {
        jdbc.update("""
            INSERT INTO weekly_ranking_revisions (period_start, revision, status, calculated_at, job_execution_id)
            VALUES (?, ?, ?, ?, 1)""", LocalDate.parse(periodStart), revision, status, LocalDateTime.parse("2026-08-24T06:00:00"));
    }

    public static void insertScore(JdbcTemplate jdbc, String periodStart, int revision, long productId, long score, Integer rank) {
        jdbc.update("""
            INSERT INTO weekly_ranking_scores (period_start, revision, product_id, score, rank_no)
            VALUES (?, ?, ?, ?, ?)""", LocalDate.parse(periodStart), revision, productId, score, rank);
    }

    public static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
