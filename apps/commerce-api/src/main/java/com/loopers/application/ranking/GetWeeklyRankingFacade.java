package com.loopers.application.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class GetWeeklyRankingFacade {
    private static final int MAX_LIMIT = 100;

    private final WeeklyRankingQuery query;

    public WeeklyRanking get(LocalDate periodStart, int limit) {
        if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY || limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        return query.findPublished(periodStart, limit)
                .map(ranking -> WeeklyRanking.published(periodStart, ranking))
                .orElseGet(() -> WeeklyRanking.notPublished(periodStart));
    }
}
