package com.loopers.application.product.facade;

import com.loopers.application.product.dto.WeeklyRankingInfo;
import com.loopers.domain.product.service.WeeklyRankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@RequiredArgsConstructor
@Component
public class WeeklyRankingFacade {

    private final WeeklyRankingService weeklyRankingService;

    public WeeklyRankingInfo getWeeklyRanking(LocalDate periodStart, int limit) {
        return WeeklyRankingInfo.from(weeklyRankingService.getWeeklyRanking(periodStart, limit));
    }
}
