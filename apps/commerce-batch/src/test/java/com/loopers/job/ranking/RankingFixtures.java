package com.loopers.job.ranking;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * batch 테스트용 DB 준비.
 * 원본 product_purchase_activity는 commerce-streamer의 엔티티라 batch 테스트 DB에는 생기지 않으므로,
 * Hibernate가 만드는 것과 같은 컬럼으로 직접 만든다 (docs/week9/design.md D2. 실제 테이블과의 일치는 E2E가 확인한다).
 * Batch metadata는 지우지 않는다. 테스트끼리는 서로 다른 periodStart를 써서 JobInstance가 겹치지 않게 한다.
 */
final class RankingFixtures {

    private final JdbcTemplate jdbc;

    RankingFixtures(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS product_purchase_activity (
              id BIGINT NOT NULL AUTO_INCREMENT,
              created_at DATETIME(6) NOT NULL,
              updated_at DATETIME(6) NOT NULL,
              deleted_at DATETIME(6) NULL,
              order_id BIGINT NOT NULL,
              product_id BIGINT NOT NULL,
              quantity INT NOT NULL,
              confirmed_at DATETIME(6) NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_product_purchase_activity_order_product (order_id, product_id),
              KEY idx_product_purchase_activity_product (product_id, confirmed_at)
            ) ENGINE=InnoDB""");
    }

    /** 원본 활동과 랭킹 테이블을 비운다. Batch metadata는 남긴다. */
    void clearData() {
        for (String table : List.of(
            "product_purchase_activity", "ranking_snapshot", "ranking_snapshot_input",
            "ranking_score_stage", "weekly_ranking", "ranking_publication")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    void activity(long orderId, long productId, int quantity, String confirmedAtUtc) {
        LocalDateTime confirmedAt = LocalDateTime.ofInstant(Instant.parse(confirmedAtUtc), ZoneOffset.UTC);
        jdbc.update("""
                insert into product_purchase_activity (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
                values (now(6), now(6), ?, ?, ?, ?)""",
            orderId, productId, quantity, confirmedAt);
    }

    /** 과제 §6의 합성 fixture. 원본 4행, 상품 7·8·9. 정상 결과는 7(5점·1위), 9(5점·2위), 8(1점·3위). */
    void assignmentWeek(String mondayUtc) {
        LocalDateTime monday = LocalDateTime.ofInstant(Instant.parse(mondayUtc), ZoneOffset.UTC);
        activity(42, 7, 2, monday.plusHours(1).toInstant(ZoneOffset.UTC).toString());
        activity(42, 8, 1, monday.plusHours(1).toInstant(ZoneOffset.UTC).toString());
        activity(43, 7, 3, monday.plusDays(1).plusHours(1).toInstant(ZoneOffset.UTC).toString());
        activity(44, 9, 5, monday.plusDays(2).plusHours(1).toInstant(ZoneOffset.UTC).toString());
    }

    List<Map<String, Object>> snapshotInput(String snapshotId) {
        return jdbc.queryForList(
            "select order_id, product_id, quantity from ranking_snapshot_input where snapshot_id = ? order by order_id, product_id",
            snapshotId);
    }

    List<Map<String, Object>> snapshots() {
        return jdbc.queryForList("select * from ranking_snapshot order by snapshot_id");
    }

    Map<String, Object> snapshot(String snapshotId) {
        return jdbc.queryForMap("select * from ranking_snapshot where snapshot_id = ?", snapshotId);
    }

    List<Map<String, Object>> stage(String snapshotId) {
        return jdbc.queryForList(
            "select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id", snapshotId);
    }

    List<Map<String, Object>> ranking(String snapshotId) {
        return jdbc.queryForList(
            "select product_id, score, rank_no from weekly_ranking where snapshot_id = ? order by rank_no", snapshotId);
    }

    List<Map<String, Object>> publications() {
        return jdbc.queryForList("select period_start, snapshot_id, revision from ranking_publication order by period_start");
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
