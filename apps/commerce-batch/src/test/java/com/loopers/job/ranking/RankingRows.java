package com.loopers.job.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;

// 3단계 테스트가 함께 쓰는 원본 준비·결과 조회. Batch metadata와 랭킹 행은 지우지 않으므로 테스트마다 다른 주(무작위 월요일)를 쓴다.
// 공개 pointer는 주마다 하나라서 같은 주를 여러 테스트가 쓰면 서로의 revision을 보게 된다.
final class RankingRows {
    // 2030-01-07은 월요일이다. 이 뒤로 무작위 주를 고른다(snapshotId 날짜가 8자리인 범위 안).
    private static final LocalDate FIRST_MONDAY = LocalDate.of(2030, 1, 7);

    private final JdbcTemplate jdbc;

    RankingRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDate randomMonday() {
        return FIRST_MONDAY.plusWeeks(ThreadLocalRandom.current().nextLong(0, 300_000));
    }

    static String snapshotId(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    // 과제 §6 입력 4행을 그 주로 옮긴 것. 점수는 {7:5, 8:1, 9:5}다.
    void insertFixture(LocalDate periodStart) {
        source(periodStart, 42, 7, 2, 0);
        source(periodStart, 42, 8, 1, 0);
        source(periodStart, 43, 7, 3, 1);
        source(periodStart, 44, 9, 5, 2);
    }

    void source(LocalDate periodStart, long orderId, long productId, int quantity, int dayOffset) {
        LocalDateTime confirmedAt = periodStart.plusDays(dayOffset).atTime(1, 0);
        jdbc.update("insert into product_purchase_activity(order_id, product_id, quantity, confirmed_at, created_at)"
            + " values (?, ?, ?, ?, ?)", orderId, productId, quantity, confirmedAt, confirmedAt);
    }

    void clearSource() {
        jdbc.update("delete from product_purchase_activity");
    }

    // calc_rank 순서의 "순위/상품/점수".
    List<String> results(String snapshotId) {
        return jdbc.queryForList("select calc_rank, product_id, score from ranking_result where snapshot_id = ?"
                + " order by calc_rank", snapshotId).stream()
            .map(row -> row.get("calc_rank") + "/" + row.get("product_id") + "/" + row.get("score"))
            .toList();
    }

    Optional<String> pointer(LocalDate periodStart) {
        return jdbc.queryForList("select snapshot_id from ranking_publication where period_start = ?", String.class, periodStart)
            .stream().findFirst();
    }

    List<Integer> revisions(LocalDate periodStart) {
        return jdbc.queryForList("select revision from ranking_snapshot where period_start = ? order by revision", Integer.class,
            periodStart);
    }

    List<Map<String, Object>> stage(String snapshotId) {
        return jdbc.queryForList("select product_id, score from ranking_score_stage where snapshot_id = ? order by product_id",
            snapshotId);
    }
}
