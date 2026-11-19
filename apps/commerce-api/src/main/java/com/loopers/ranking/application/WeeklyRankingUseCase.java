package com.loopers.ranking.application;

import com.loopers.ranking.domain.WeeklyRanking;
import com.loopers.ranking.domain.WeeklyRankingPeriod;
import com.loopers.ranking.domain.WeeklyRankingRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class WeeklyRankingUseCase {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final WeeklyRankingRepository repository;

    public WeeklyRankingUseCase(WeeklyRankingRepository repository) {
        this.repository = repository;
    }

    public WeeklyRanking get(String periodStart, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorCode.INVALID_REQUEST);
        }
        return repository.findByPeriod(WeeklyRankingPeriod.parse(periodStart), limit);
    }
}
