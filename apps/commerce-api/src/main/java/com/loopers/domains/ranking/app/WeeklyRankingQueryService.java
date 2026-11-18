package com.loopers.domains.ranking.app;

import com.loopers.domains.ranking.app.port.PublishedRanking;
import com.loopers.domains.ranking.app.port.WeeklyRankingReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

// 고객 주간 랭킹(docs/week9/commerce-ranking.md 10절). 응답은 공개된 snapshot 기준이고, 순위는 삭제 상품을 뺀 표시 순위다(D157).
@Service
public class WeeklyRankingQueryService {
    private final WeeklyRankingReader weeklyRankingReader;

    public WeeklyRankingQueryService(WeeklyRankingReader weeklyRankingReader) {
        this.weeklyRankingReader = weeklyRankingReader;
    }

    public WeeklyRankingResult weekly(LocalDate periodStart, int limit) {
        LocalDate periodEnd = periodStart.plusDays(7);
        Optional<PublishedRanking> published = weeklyRankingReader.findPublished(periodStart, limit);
        if (published.isEmpty()) {
            return new WeeklyRankingResult(WeeklyRankingResult.NOT_PUBLISHED, periodStart, periodEnd, null, null, List.of());
        }
        List<WeeklyRankingResult.Item> items = new ArrayList<WeeklyRankingResult.Item>();
        for (PublishedRanking.RankedProduct product : published.get().products()) {
            items.add(new WeeklyRankingResult.Item(items.size() + 1, product.productId(), product.name(), product.price(),
                product.brandId(), product.brandName(), product.score(), product.stock() == 0));
        }
        return new WeeklyRankingResult(WeeklyRankingResult.PUBLISHED, periodStart, periodEnd, published.get().snapshotId(),
            published.get().frozenAt(), items);
    }
}
