package com.loopers.ranking.adapter.in.web.spec;

import com.loopers.ranking.adapter.in.web.dto.RankingDto;
import com.loopers.support.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ranking V1 API", description = "주간 구매 랭킹 API")
public interface RankingApiSpec {

    @Operation(
        summary = "주간 구매 랭킹 조회",
        description = "periodStart(UTC 월요일, 필수) 주의 공개된 완성본만 돌려준다. 공개본이 없으면 NOT_PUBLISHED + 빈 목록, "
            + "구매 0건 완성본은 PUBLISHED + 빈 목록. 삭제 상품은 빼고 저장된 순위 번호는 유지하므로 번호가 빌 수 있다. limit 1–100, 기본 20."
    )
    ApiResponse<RankingDto.WeeklyRankingResponse> getWeeklyRanking(String periodStart, Integer limit);
}
