package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingFacade;
import com.loopers.application.ranking.WeeklyRankingInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주간 구매 랭킹 조회 (설계 D14). 상품 목록 API 와 같게 X-USER-ID 헤더를 받지 않는다.
 * periodStart 누락·limit 타입 불일치는 ApiControllerAdvice 가 400 으로 바꾼다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingV1Controller {

    private final WeeklyRankingFacade weeklyRankingFacade;

    @GetMapping("/weekly")
    public ApiResponse<RankingV1Dto.WeeklyRankingResponse> getWeeklyRanking(
        @RequestParam(value = "periodStart") String periodStart,
        @RequestParam(value = "limit", defaultValue = "10") int limit
    ) {
        WeeklyRankingInfo info = weeklyRankingFacade.getWeeklyRanking(periodStart, limit);
        return ApiResponse.success(RankingV1Dto.WeeklyRankingResponse.from(info));
    }
}
