package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRanking;
import com.loopers.domain.ranking.PublishedRankingRepository;
import com.loopers.domain.ranking.VisibleRankingProducts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

@Component
public class WeeklyRankingReader {
    private final PublishedRankingRepository rankings;
    private final VisibleRankingProducts products;

    public WeeklyRankingReader(PublishedRankingRepository rankings, VisibleRankingProducts products) {
        this.rankings = rankings;
        this.products = products;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WeeklyRankingInfo read(PublishedRanking snapshot, int limit) {
        var items = new ArrayList<WeeklyRankingInfo.Item>();
        long afterRank = 0;
        long previousScore = Long.MAX_VALUE;
        long previousProduct = 0;
        while (afterRank < snapshot.resultCount() && items.size() < limit) {
            var candidates = rankings.findCandidates(snapshot.snapshotId(), afterRank, 64);
            if (candidates.isEmpty()) {
                throw new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.CORRUPT);
            }
            var visible = products.names(candidates.stream().map(candidate -> candidate.productId()).toList());
            for (var candidate : candidates) {
                if (candidate.rank() != afterRank + 1 || candidate.rank() > snapshot.resultCount()
                    || candidate.productId() < 1 || candidate.score() < 1 || candidate.score() > previousScore
                    || (candidate.score() == previousScore && candidate.productId() <= previousProduct)) {
                    throw new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.CORRUPT);
                }
                afterRank = candidate.rank();
                previousScore = candidate.score();
                previousProduct = candidate.productId();
                String name = visible.get(candidate.productId());
                if (name != null) {
                    items.add(new WeeklyRankingInfo.Item(candidate.productId(), name, candidate.score(), candidate.rank()));
                    if (items.size() == limit) {
                        break;
                    }
                }
            }
        }
        return new WeeklyRankingInfo("PUBLISHED", snapshot.periodStart(), snapshot.periodEnd(), snapshot.snapshotId(),
            snapshot.revision(), snapshot.frozenAt(), items);
    }
}
