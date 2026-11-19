package com.loopers.ranking.domain;

public interface WeeklyRankingRepository {

    WeeklyRanking findByPeriod(WeeklyRankingPeriod period, int limit);
}
