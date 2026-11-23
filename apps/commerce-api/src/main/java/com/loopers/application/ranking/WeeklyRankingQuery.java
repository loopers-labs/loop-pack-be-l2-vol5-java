package com.loopers.application.ranking;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingQuery {
    Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit);
}
