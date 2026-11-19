package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingPeriod;
import com.loopers.domain.ranking.WeeklyRankingPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingPeriodRepositoryImpl implements WeeklyRankingPeriodRepository {
    private final WeeklyRankingPeriodJpaRepository jpaRepository;

    @Override
    public Optional<WeeklyRankingPeriod> findForUpdate(LocalDate periodStart) {
        return jpaRepository.findForUpdate(periodStart);
    }

    @Override
    public WeeklyRankingPeriod saveAndFlush(WeeklyRankingPeriod period) {
        return jpaRepository.saveAndFlush(period);
    }
}
