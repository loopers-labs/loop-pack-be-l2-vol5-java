package com.loopers.ranking.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.Requester;
import com.loopers.ranking.application.WeeklyRankingUseCase;
import com.loopers.ranking.domain.WeeklyRanking;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rankings/weekly")
public class WeeklyRankingV1Controller {

    private final WeeklyRankingUseCase weeklyRanking;

    public WeeklyRankingV1Controller(WeeklyRankingUseCase weeklyRanking) {
        this.weeklyRanking = weeklyRanking;
    }

    @GetMapping
    public ApiResponse<WeeklyRanking> getWeeklyRanking(
        Requester requester,
        @RequestParam String periodStart,
        @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(weeklyRanking.get(periodStart, limit));
    }
}
