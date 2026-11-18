package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.query.WeeklyRankingReader;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class RankingV1Controller implements RankingV1ApiSpec {

    private final WeeklyRankingReader weeklyRankingReader;

    /** 값 검증(월요일·limit 범위)은 Reader 가 하고 ErrorType 으로 돌려준다. 여기서는 문자열 그대로 넘긴다. */
    @GetMapping("/api/v1/rankings/weekly")
    @Override
    public ApiResponse<RankingV1Dto.WeeklyRankingResponse> getWeekly(
        @RequestParam(name = "periodStart", required = false) String periodStart,
        @RequestParam(name = "limit", required = false) String limit
    ) {
        return ApiResponse.success(RankingV1Dto.WeeklyRankingResponse.from(weeklyRankingReader.getWeekly(periodStart, limit)));
    }
}
