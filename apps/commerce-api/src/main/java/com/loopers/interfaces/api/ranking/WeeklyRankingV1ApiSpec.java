package com.loopers.interfaces.api.ranking;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;

import java.time.LocalDate;

public interface WeeklyRankingV1ApiSpec {

    @Operation(
        summary = "주간 구매 랭킹 조회",
        description = "UTC 월요일부터 시작하는 한 주에서 가장 최근에 완성된 회차의 구매 랭킹을 계산 순위 순으로 제공합니다. 완성된 회차가 없으면 NOT_PUBLISHED입니다."
    )
    ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse> getWeekly(LocalDate periodStart, int limit);
}
