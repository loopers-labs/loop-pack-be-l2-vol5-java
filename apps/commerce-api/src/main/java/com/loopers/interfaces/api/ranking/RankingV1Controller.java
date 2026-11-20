package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingQueryService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingV1Controller {

    private final WeeklyRankingQueryService weeklyRankings;

    @GetMapping("/weekly")
    public ApiResponse<RankingV1Dto.WeeklyRankingResponse> getWeekly(
        @RequestParam String periodStart,
        @RequestParam(defaultValue = "" + WeeklyRankingQueryService.DEFAULT_LIMIT) int limit
    ) {
        return ApiResponse.success(RankingV1Dto.WeeklyRankingResponse.from(weeklyRankings.get(periodStart, limit)));
    }
}
