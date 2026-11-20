package com.loopers.ranking.adapter.in.web;

import com.loopers.ranking.adapter.in.web.dto.RankingDto;
import com.loopers.ranking.adapter.in.web.spec.RankingApiSpec;
import com.loopers.ranking.application.RankingQueryService;
import com.loopers.support.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingController implements RankingApiSpec {

    private final RankingQueryService rankingQueryService;

    @GetMapping("/weekly")
    @Override
    public ApiResponse<RankingDto.WeeklyRankingResponse> getWeeklyRanking(
        @RequestParam(required = false) String periodStart,
        @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(RankingDto.WeeklyRankingResponse.from(
            rankingQueryService.getWeeklyRanking(RankingDto.parsePeriodStart(periodStart), RankingDto.parseLimit(limit))
        ));
    }
}
