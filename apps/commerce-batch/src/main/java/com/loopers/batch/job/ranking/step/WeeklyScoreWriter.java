package com.loopers.batch.job.ranking.step;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * (snapshotId, productId) 에 절대 점수를 upsert 한다. 같은 상품을 다시 써도 score = total 로 수렴한다 (9주차 D-181).
 * chunk TX 에 참여하므로 결과와 reader 위치가 함께 commit 된다
 */
@RequiredArgsConstructor
@Component
public class WeeklyScoreWriter implements ItemWriter<ProductScore> {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public void write(Chunk<? extends ProductScore> chunk) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<? extends ProductScore> scores = chunk.getItems();
        jdbc.batchUpdate("""
                insert into weekly_ranking_score (snapshot_id, product_id, score, created_at, updated_at)
                values (?, ?, ?, ?, ?) as new
                on duplicate key update score = new.score, updated_at = new.updated_at""",
            scores, scores.size(), (ps, score) -> {
                ps.setString(1, score.snapshotId());
                ps.setLong(2, score.productId());
                ps.setLong(3, score.score());
                ps.setObject(4, now);
                ps.setObject(5, now);
            });
    }
}
