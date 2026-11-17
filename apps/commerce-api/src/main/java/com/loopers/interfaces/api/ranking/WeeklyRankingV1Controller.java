package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.RankingFacade;
import com.loopers.domain.ranking.WeeklyRankingQuery;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class WeeklyRankingV1Controller implements WeeklyRankingV1ApiSpec {

    private final RankingFacade rankingFacade;

    /** periodStart·limit 은 생략과 빈 값을 구분하려고 raw 문자열로 받는다. */
    @GetMapping("/weekly")
    @Override
    public ApiResponse<WeeklyRankingV1Dto.WeeklyResponse> getWeeklyRanking(
        @CustomerId Long userId,
        @RequestParam(required = false) String periodStart,
        @RequestParam(required = false) String limit
    ) {
        WeeklyRankingQuery query = WeeklyRankingQuery.of(periodStart, limit);
        return ApiResponse.success(WeeklyRankingV1Dto.WeeklyResponse.from(rankingFacade.getWeeklyRanking(query)));
    }
}
