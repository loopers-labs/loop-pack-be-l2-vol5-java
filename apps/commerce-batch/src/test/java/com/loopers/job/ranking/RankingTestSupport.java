package com.loopers.job.ranking;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 주간 랭킹 batch 테스트 공통 헬퍼. 입력 원본 product_purchase_activity 는 streamer 엔티티라 batch classpath 에 없으므로
 * DDL 을 상수로 두고 직접 만든다 (설계 8절). 컬럼은 streamer ProductPurchaseActivity + BaseEntity 와 1:1 이며, 엔티티가 바뀌면 이 문자열도 따라 고쳐야 한다.
 */
final class RankingTestSupport {

    static final String PRODUCT_PURCHASE_ACTIVITY_DDL = """
        CREATE TABLE IF NOT EXISTS product_purchase_activity (
            id           BIGINT      NOT NULL AUTO_INCREMENT,
            order_id     BIGINT      NOT NULL,
            product_id   BIGINT      NOT NULL,
            quantity     INT         NOT NULL,
            confirmed_at DATETIME(6) NOT NULL,
            created_at   DATETIME(6) NOT NULL,
            updated_at   DATETIME(6) NOT NULL,
            deleted_at   DATETIME(6) NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_ppa_order_product (order_id, product_id)
        ) ENGINE=InnoDB
        """;

    /** 과제 §6 기대값 (orderId 42·43·44 기준). */
    static final String FIXTURE_INPUT_CHECKSUM = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    static final String FIXTURE_RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;

    RankingTestSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void createPurchaseActivityTable() {
        jdbcTemplate.execute(PRODUCT_PURCHASE_ACTIVITY_DDL);
    }

    static JobParameters params(LocalDate periodStart, int revision) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart.toString(), true)
            .addString("snapshotId", snapshotId(periodStart, revision), true)
            .toJobParameters();
    }

    static String snapshotId(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.toString().replace("-", "") + "-r" + revision;
    }

    static LocalDateTime utc(String iso) {
        return ZonedDateTime.parse(iso).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 구매 활동 1행. confirmedAt 은 UTC 벽시계로 바인딩한다 (D12). */
    void insertActivity(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc, LocalDateTime deletedAtUtc) {
        jdbcTemplate.update(
            "INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, current_timestamp(6), current_timestamp(6), ?)",
            orderId, productId, quantity, confirmedAtUtc, deletedAtUtc
        );
    }

    /**
     * §6 fixture 4행을 monday 가 시작인 주에 넣는다. orderBase 를 더해 테스트 간 (order_id, product_id) 유일성을 지킨다.
     * orderBase 0 이면 orderId 42·43·44 그대로라 기대 checksum 상수와 일치한다.
     */
    void insertFixture(LocalDate monday, long orderBase) {
        LocalDateTime day1 = monday.atTime(1, 0);
        LocalDateTime day2 = monday.plusDays(1).atTime(1, 0);
        LocalDateTime day3 = monday.plusDays(2).atTime(1, 0);
        insertActivity(orderBase + 42, 7, 2, day1, null);
        insertActivity(orderBase + 42, 8, 1, day1, null);
        insertActivity(orderBase + 43, 7, 3, day2, null);
        insertActivity(orderBase + 44, 9, 5, day3, null);
    }

    int countInputs(String snapshotId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_ranking_inputs WHERE snapshot_id = ?", Integer.class, snapshotId);
    }

    Map<String, Object> revision(String snapshotId) {
        return jdbcTemplate.queryForMap("SELECT * FROM weekly_ranking_revisions WHERE snapshot_id = ?", snapshotId);
    }

    /** product_id 순 (product_id, score, ranking). ranking 은 null 가능. */
    List<Map<String, Object>> scores(String snapshotId) {
        return jdbcTemplate.queryForList(
            "SELECT product_id, score, ranking FROM weekly_ranking_scores WHERE snapshot_id = ? ORDER BY product_id", snapshotId);
    }

    List<Map<String, Object>> publication(LocalDate periodStart) {
        return jdbcTemplate.queryForList("SELECT * FROM weekly_ranking_publications WHERE period_start = ?", periodStart);
    }
}
