package com.loopers.application.ranking.query;

import java.util.Optional;

public interface WeeklyRankingQueryDao {
    Optional<WeeklyRankingView> findPublished(WeeklyRankingCriteria criteria);
}
