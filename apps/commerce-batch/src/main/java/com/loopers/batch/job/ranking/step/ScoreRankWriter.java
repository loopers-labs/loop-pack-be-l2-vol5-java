package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductRank;
import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 묶음의 순위를 계산 중인 회차에 절대값으로 쓴다. 같은 묶음을 다시 써도 같은 값이 된다.
 * 바뀐 행 수가 아이템 수와 다르면 그사이 회차가 닫힌 것이므로 실패해 그 묶음을 되돌린다.
 */
public class ScoreRankWriter implements ItemWriter<ProductRank> {

    private final WeeklyRankingRepository repository;
    private final WeeklyRankingKey key;

    public ScoreRankWriter(WeeklyRankingRepository repository, WeeklyRankingKey key) {
        this.repository = repository;
        this.key = key;
    }

    @Override
    public void write(Chunk<? extends ProductRank> chunk) {
        int written = repository.writeRanks(key.period().start(), key.revision(), chunk.getItems());
        if (written != chunk.size()) {
            throw new IllegalStateException(
                "계산 중인 회차가 아니라 순위를 쓰지 못했다. key=" + key + ", items=" + chunk.size() + ", written=" + written);
        }
    }
}
