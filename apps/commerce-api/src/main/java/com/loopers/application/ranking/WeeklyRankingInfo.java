package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRevision;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.RankingPeriod;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 주간 랭킹 조회 결과. NOT_PUBLISHED면 revision과 calculatedAt은 null이고 items는 빈 목록이다.
 */
public record WeeklyRankingInfo(
    WeeklyRankingStatus status,
    LocalDate periodStart,
    LocalDate periodEnd,
    Integer revision,
    Instant calculatedAt,
    List<RankedProduct> items
) {

    public static WeeklyRankingInfo notPublished(RankingPeriod period) {
        return new WeeklyRankingInfo(WeeklyRankingStatus.NOT_PUBLISHED, period.start(), period.end(), null, null, List.of());
    }

    public static WeeklyRankingInfo published(RankingPeriod period, PublishedRevision published, List<RankedProduct> items) {
        return new WeeklyRankingInfo(WeeklyRankingStatus.PUBLISHED, period.start(), period.end(),
            published.revision(), published.calculatedAt(), List.copyOf(items));
    }
}
