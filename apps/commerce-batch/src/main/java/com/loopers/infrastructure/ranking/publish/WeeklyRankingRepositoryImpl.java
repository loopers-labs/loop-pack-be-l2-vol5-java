package com.loopers.infrastructure.ranking.publish;

import com.loopers.domain.ranking.publish.WeeklyRanking;
import com.loopers.domain.ranking.publish.WeeklyRankingRepository;
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
