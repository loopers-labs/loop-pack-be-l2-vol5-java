package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingPublication;
import com.loopers.domain.ranking.WeeklyRankingPublicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingPublicationRepositoryImpl implements WeeklyRankingPublicationRepository {
    private final WeeklyRankingPublicationJpaRepository jpaRepository;

    @Override
    public Optional<WeeklyRankingPublication> findForUpdate(LocalDate periodStart) {
        return jpaRepository.findForUpdate(periodStart);
    }

    @Override
    public WeeklyRankingPublication saveAndFlush(WeeklyRankingPublication publication) {
        return jpaRepository.saveAndFlush(publication);
    }
}
