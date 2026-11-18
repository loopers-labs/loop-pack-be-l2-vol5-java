package com.loopers.interfaces.api.ranking;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ranking V1 API", description = "주간 구매 랭킹 고객 API")
public interface RankingV1ApiSpec {

    /** GET /api/v1/rankings/weekly */
    @Operation(summary = "주간 구매 랭킹",
        description = "기간(UTC 월요일부터 7일)의 공개된 완성 랭킹. 공개본이 없으면 NOT_PUBLISHED·빈 items. "
            + "삭제된 상품은 빠지고 저장된 계산 순위는 그대로라 순위에 간격이 있을 수 있다. 공개 조건을 적용한 뒤 limit 개.")
    ApiResponse<RankingV1Dto.WeeklyRankingResponse> getWeekly(
        @Parameter(description = "UTC 월요일, yyyy-MM-dd", required = true) String periodStart,
        @Parameter(description = "1 ~ 100, 기본 10") String limit
    );
}
