package com.loopers.batch.job.ranking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 랭킹 테스트의 원본 fixture 와 조회. batch metadata 는 지우지 않는다 (실습 §8) */
@Component
public class RankingTables {

    private static final List<String> TABLES = List.of(
        "processed_order_confirmation", "product_purchase_activity",
        "weekly_ranking_snapshot", "weekly_ranking_input", "weekly_ranking_score");

    private final JdbcTemplate jdbc;

    public RankingTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void truncate() {
        TABLES.forEach(table -> jdbc.execute("truncate table " + table));
    }

    /** W6 consumer 가 남기는 모양: 처리 기록 1행 + 품목별 활동. 시각은 UTC 벽시계 값 */
    public void confirm(long orderId, String confirmedAtUtc, Map<Long, Integer> quantityByProduct) {
        jdbc.update("""
            insert into processed_order_confirmation (event_id, order_id, confirmed_at, created_at, updated_at)
            values (?, ?, ?, now(6), now(6))""", "event-" + orderId, orderId, confirmedAtUtc);
        quantityByProduct.forEach((productId, quantity) -> jdbc.update("""
            insert into product_purchase_activity (order_id, product_id, quantity, created_at, updated_at)
            values (?, ?, ?, now(6), now(6))""", orderId, productId, quantity));
    }

    /**
     * 실습 §6 을 주어진 주에 둔다. 기대 점수는 상품 7: 5, 8: 1, 9: 5.
     * 테스트 클래스가 한 컨테이너의 batch metadata 를 공유하므로, 클래스마다 다른 주를 써서 JobInstance 가 겹치지 않게 한다
     */
    public void confirmLabFixture(LocalDate week) {
        confirm(42L, week + " 01:00:00", Map.of(7L, 2, 8L, 1));
        confirm(43L, week.plusDays(1) + " 01:00:00", Map.of(7L, 3));
        confirm(44L, week.plusDays(2) + " 01:00:00", Map.of(9L, 5));
    }

    public List<Map<String, Object>> inputsOf(String snapshotId) {
        return jdbc.queryForList(
            "select order_id, product_id, quantity from weekly_ranking_input where snapshot_id = ? order by order_id, product_id",
            snapshotId);
    }

    public List<Map<String, Object>> scoresOf(String snapshotId) {
        return jdbc.queryForList(
            "select product_id, score, ranking from weekly_ranking_score where snapshot_id = ? order by product_id", snapshotId);
    }

    public Map<String, Object> manifestOf(String snapshotId) {
        return jdbc.queryForMap("""
            select cast(period_start as char) as period_start, revision, status, input_count, input_checksum
            from weekly_ranking_snapshot where snapshot_id = ?""", snapshotId);
    }

    /** 결과 checksum. 순위 순서의 productId · score · rank 를 TAB · LF 로 직렬화해 SHA-256 (실습 §6). 실행 ID · 시각은 넣지 않는다 */
    public String resultChecksumOf(String snapshotId) {
        StringBuilder serialized = new StringBuilder();
        jdbc.query("select product_id, score, ranking from weekly_ranking_score where snapshot_id = ? order by ranking",
            rs -> {
                serialized.append(rs.getLong("product_id")).append('\t')
                    .append(rs.getLong("score")).append('\t')
                    .append(rs.getInt("ranking")).append('\n');
            },
            snapshotId);
        return sha256(serialized.toString());
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void changeStatus(String snapshotId, String status) {
        jdbc.update("update weekly_ranking_snapshot set status = ? where snapshot_id = ?", status, snapshotId);
    }
}
