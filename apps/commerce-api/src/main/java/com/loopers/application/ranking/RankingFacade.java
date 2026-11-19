package com.loopers.application.ranking;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.loopers.domain.ranking.RankingLimit;
import com.loopers.domain.ranking.WeekStart;
import com.loopers.domain.ranking.WeeklyRankingQuery;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RankingFacade {

    private final WeeklyRankingQuery weeklyRankingQuery;

    public Optional<WeeklyRankingQuery.Published> findWeekly(WeekStart week, RankingLimit limit) {
        return weeklyRankingQuery.findPublished(week, limit);
    }
}
