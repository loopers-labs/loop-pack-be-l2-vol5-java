package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class WeeklyRankingController implements WeeklyRankingApiSpec {

    private final WeeklyRankingFacade weeklyRankingFacade;

    @GetMapping("/weekly")
    @Override
    public ApiResponse<WeeklyRankingDto.WeeklyRankingResponse> getWeekly(
        @RequestParam String periodStart,
        @RequestParam(defaultValue = WeeklyRankingFacade.DEFAULT_LIMIT) int limit
    ) {
        return ApiResponse.success(WeeklyRankingDto.WeeklyRankingResponse.from(weeklyRankingFacade.getWeekly(periodStart, limit)));
    }
}
