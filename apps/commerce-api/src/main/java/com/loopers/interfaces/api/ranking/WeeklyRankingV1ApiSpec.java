package com.loopers.interfaces.api.ranking;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ranking V1 API", description = "주간 구매 수량 랭킹")
public interface WeeklyRankingV1ApiSpec {

    @Operation(summary = "주간 랭킹 조회", description = "periodStart 는 필수 UTC 월요일(YYYY-MM-DD), limit 은 1~100(기본 10). "
        + "공개본이 없으면 200 NOT_PUBLISHED, 있으면 200 PUBLISHED 이며 현재 노출 상품만 저장 순위로 반환한다.")
    ApiResponse<WeeklyRankingV1Dto.WeeklyResponse> getWeeklyRanking(Long userId, String periodStart, String limit);
}
