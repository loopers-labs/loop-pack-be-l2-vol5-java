package com.loopers.interfaces.api.ranking;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loopers.application.ranking.RankingFacade;
import com.loopers.domain.ranking.RankingLimit;
import com.loopers.domain.ranking.WeekStart;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/rankings")
@RequiredArgsConstructor
public class RankingV1Controller {

    private final RankingFacade rankingFacade;

    @GetMapping("/weekly")
    public ApiResponse<RankingV1Dto.WeeklyResponse> weekly(
        @RequestParam WeekStart periodStart,
        @RequestParam(defaultValue = "10") RankingLimit limit
    ) {
        return ApiResponse.success(
            RankingV1Dto.WeeklyResponse.of(periodStart, rankingFacade.findWeekly(periodStart, limit))
        );
    }
}
