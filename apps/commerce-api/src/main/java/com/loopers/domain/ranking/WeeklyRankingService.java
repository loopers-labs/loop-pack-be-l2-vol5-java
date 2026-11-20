package com.loopers.domain.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingService {

    private final WeeklyRankingRepository weeklyRankingRepository;

    public Optional<WeeklyRankingSnapshot> findPublished(RankingWeek week) {
        return weeklyRankingRepository.findPublishedSnapshot(week.periodStart());
    }

    public List<RankedProduct> getVisible(WeeklyRankingSnapshot snapshot, int limit) {
        return weeklyRankingRepository.findVisible(snapshot.getSnapshotId(), limit);
    }
}
