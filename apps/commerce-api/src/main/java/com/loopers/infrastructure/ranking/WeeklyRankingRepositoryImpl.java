package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.WeeklyRankingRepository;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingRepositoryImpl implements WeeklyRankingRepository {
    private final WeeklyRankingJpaRepository jpaRepository;

    @Override
    public Optional<WeeklyRankingSnapshot> findPublishedSnapshot(LocalDate periodStart) {
        return jpaRepository.findPublishedSnapshot(periodStart);
    }

    @Override
    public List<RankedProduct> findVisible(String snapshotId, int limit) {
        return jpaRepository.findVisible(snapshotId, PageRequest.of(0, limit));
    }
}
