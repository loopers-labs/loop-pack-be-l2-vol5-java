package com.loopers.batch.job.ranking.score;

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
 * chunk 의 상품 범위를 고정 입력에서 한 번에 합산해 (snapshotId, productId) 에 절대 점수를 upsert 한다 (9주차 D-181, D-197).
 * 같은 상품을 다시 써도 score = 합계로 수렴한다. chunk TX 에 참여하므로 결과와 reader 위치가 함께 commit 된다
 */
@RequiredArgsConstructor
@Component
public class WeeklyScoreWriter implements ItemWriter<ProductKey> {

    // 읽는 곳은 배치만 쓰는 snapshot 입력이라 INSERT … SELECT 의 공유 잠금이 streamer 를 막지 않는다 (D-177 과 다른 점)
    private static final String UPSERT_RANGE_SQL = """
        insert into weekly_ranking_score (snapshot_id, product_id, score, created_at, updated_at)
        select agg.snapshot_id, agg.product_id, agg.total, agg.written_at, agg.written_at
        from (
            select snapshot_id, product_id, sum(quantity) as total, ? as written_at
            from weekly_ranking_input
            where snapshot_id = ? and product_id between ? and ?
            group by snapshot_id, product_id
        ) as agg
        on duplicate key update score = agg.total, updated_at = agg.written_at""";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public void write(Chunk<? extends ProductKey> chunk) {
        List<? extends ProductKey> keys = chunk.getItems();
        if (keys.isEmpty()) {
            return;
        }
        requireContiguousSlice(keys);
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        ProductKey first = keys.getFirst();
        jdbc.update(UPSERT_RANGE_SQL, now, first.snapshotId(), first.productId(), keys.getLast().productId());
    }

    /**
     * reader 가 같은 입력의 서로 다른 상품을 오름차순으로 주므로, 첫 ~ 마지막 범위의 입력 상품이 곧 이 chunk 의 상품이다.
     * 순서나 snapshot 이 어긋나면 범위가 다른 상품을 덮으므로 쓰지 않는다
     */
    private void requireContiguousSlice(List<? extends ProductKey> keys) {
        for (int i = 1; i < keys.size(); i++) {
            ProductKey previous = keys.get(i - 1);
            ProductKey current = keys.get(i);
            if (!current.snapshotId().equals(previous.snapshotId()) || current.productId() <= previous.productId()) {
                throw new IllegalStateException("chunk 상품이 한 snapshot 의 오름차순이 아님. previous=" + previous + " current=" + current);
            }
        }
    }
}
