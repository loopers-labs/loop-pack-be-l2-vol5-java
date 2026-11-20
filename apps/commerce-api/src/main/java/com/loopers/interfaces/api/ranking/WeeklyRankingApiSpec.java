package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingFacade;
import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ranking V1 API", description = "주간 구매 랭킹 조회 API 입니다.")
public interface WeeklyRankingApiSpec {

    @Operation(
        summary = "주간 구매 랭킹 조회",
        description = "요청한 주의 공개된 완성본만 조회합니다. 기간은 periodStart(UTC 월요일)부터 periodEnd(다음 월요일, 포함하지 않음) 전까지이고 "
            + "점수는 그 주에 확정된 상품별 구매 수량의 합입니다. 공개본이 없으면 status=NOT_PUBLISHED 와 빈 items, "
            + "구매가 없던 완성본은 status=PUBLISHED 와 snapshotId, 빈 items 입니다. 삭제된 상품은 빼고 limit 을 적용하며, "
            + "rank 는 저장된 계산 순위를 그대로 보여 주므로 1·3위처럼 간격이 생길 수 있습니다. 상품 이름·가격·브랜드는 현재 값입니다."
    )
    ApiResponse<WeeklyRankingDto.WeeklyRankingResponse> getWeekly(
        @Parameter(name = "periodStart", description = "조회할 주의 시작일(yyyy-MM-dd, 월요일)")
        String periodStart,
        @Parameter(name = "limit", description = "최대 항목 수 1~" + WeeklyRankingFacade.MAX_LIMIT + " (기본 " + WeeklyRankingFacade.DEFAULT_LIMIT + ")")
        int limit
    );
}
