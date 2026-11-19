package com.loopers.domain.ranking;

import java.util.List;

public interface WeeklyRankingRepository {

    void saveAll(List<WeeklyRanking> rankings);
}
