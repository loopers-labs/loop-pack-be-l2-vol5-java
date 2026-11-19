package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRanking;
import com.loopers.domain.ranking.WeeklyRankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class WeeklyRankingRepositoryImpl implements WeeklyRankingRepository {
    private final WeeklyRankingJpaRepository jpaRepository;

    @Override
    public void saveAll(List<WeeklyRanking> rankings) {
        jpaRepository.saveAll(rankings);
    }
}
