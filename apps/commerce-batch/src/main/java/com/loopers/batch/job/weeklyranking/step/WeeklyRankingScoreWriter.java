package com.loopers.batch.job.weeklyranking.step;

import com.loopers.domain.ranking.RankingProductScore;
import com.loopers.domain.ranking.RankingScoreRepository;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.List;

/** chunk TX 안에서 절대 점수를 upsert 한다. 예외는 잡지 않고 전파해 chunk 전체를 rollback 한다. */
@RequiredArgsConstructor
public class WeeklyRankingScoreWriter implements ItemWriter<RankingProductScore> {

    private final WeeklyRankingRequest request;
    private final RankingScoreRepository repository;

    @Override
    public void write(Chunk<? extends RankingProductScore> chunk) {
        repository.upsertScores(request.snapshotId(), List.copyOf(chunk.getItems()));
    }
}
