package com.loopers.job.ranking;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 랭킹 테스트의 DB 준비·관찰. 확인은 매번 새 조회다 (reader·step 의 메모리 값을 보지 않는다).
 * 행을 지우지 않는다: 같은 JVM 의 테스트는 서로 다른 주·snapshot 을 쓴다. 활동 fixture 는 같은 행이면 다시 넣지 않는다.
 */
public class RankingTestDb {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public RankingTestDb(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 배포 SQL(docs/week9) + 테스트용 TB-13. 둘 다 IF NOT EXISTS. */
    public void prepareSchema() {
        String rankingSql = System.getProperty("ranking.weekly-sql");
        if (rankingSql == null) {
            throw new IllegalStateException("system property ranking.weekly-sql 이 없습니다 (gradle test task 가 넣는다)");
        }
        runScript(new FileSystemResource(rankingSql));
        runScript(new ClassPathResource("sql/product-purchase-activity.sql"));
    }

    private void runScript(Resource script) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, script);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 실행 실패 [" + script + "]", e);
        }
    }

    /** W6 consumer 가 남기는 구매 활동 한 행. 이미 같은 (orderId, productId) 가 있으면 내용이 같아야 한다. */
    public void activity(long orderId, long productId, int quantity, String confirmedAtUtc) {
        LocalDateTime confirmedAt = LocalDateTime.ofInstant(Instant.parse(confirmedAtUtc), ZoneOffset.UTC);
        List<Map<String, Object>> existing = jdbc.queryForList(
            "select quantity, confirmed_at from product_purchase_activity where order_id = ? and product_id = ?", orderId, productId);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (((Number) row.get("quantity")).intValue() != quantity || !confirmedAt.equals(row.get("confirmed_at"))) {
                throw new IllegalStateException("다른 내용의 같은 활동이 이미 있습니다 [orderId = " + orderId + ", productId = " + productId + "]");
            }
            return;
        }
        jdbc.update("insert into product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?)", orderId, productId, quantity, confirmedAt, confirmedAt, confirmedAt);
    }

    /** §6 합성 fixture: 주문 42 (7×2, 8×1), 43 (7×3), 44 (9×5). 2026-08-17 주. */
    public void section6Fixture() {
        activity(42, 7, 2, "2026-08-17T01:00:00Z");
        activity(42, 8, 1, "2026-08-17T01:00:00Z");
        activity(43, 7, 3, "2026-08-18T01:00:00Z");
        activity(44, 9, 5, "2026-08-19T01:00:00Z");
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** (product_id, score), product_id 순. */
    public List<List<Long>> stage(String snapshotId) {
        return jdbc.query("select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id",
            (rs, n) -> List.of(rs.getLong("product_id"), rs.getLong("score")), snapshotId);
    }

    /** (product_id, score, rank_no), rank 순. */
    public List<List<Long>> ranking(String snapshotId) {
        return jdbc.query("select product_id, score, rank_no from weekly_ranking where snapshot_id = ? order by rank_no",
            (rs, n) -> List.of(rs.getLong("product_id"), rs.getLong("score"), rs.getLong("rank_no")), snapshotId);
    }

    /** (order_id, product_id, quantity), 정렬. */
    public List<List<Long>> inputs(String snapshotId) {
        return jdbc.query("select order_id, product_id, quantity from ranking_snapshot_input where snapshot_id = ? order by order_id, product_id",
            (rs, n) -> List.of(rs.getLong("order_id"), rs.getLong("product_id"), rs.getLong("quantity")), snapshotId);
    }

    public Optional<Map<String, Object>> snapshot(String snapshotId) {
        return jdbc.queryForList("select * from ranking_snapshot where snapshot_id = ?", snapshotId).stream().findFirst();
    }

    public Optional<Map<String, Object>> publication(LocalDate periodStart) {
        return jdbc.queryForList("select * from weekly_ranking_publication where period_start = ?", periodStart).stream().findFirst();
    }

    public Optional<String> publishedSnapshotId(LocalDate periodStart) {
        return publication(periodStart).map(row -> (String) row.get("snapshot_id"));
    }
}
