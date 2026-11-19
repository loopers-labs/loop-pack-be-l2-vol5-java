package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;

@Tag(name = "Weekly Ranking V1 API", description = "주간 구매 수량 랭킹 조회 API 입니다. 요청자를 식별하지 않습니다.")
public interface WeeklyRankingV1ApiSpec {

    @Operation(
        summary = "주간 구매 랭킹",
        description = "공개된 완성본만 읽습니다. 점수는 그 주(UTC 월요일 00:00 ~ 다음 월요일 00:00 전)에 확정된 구매 수량 합이고, "
            + "순위는 계산 당시 순위입니다. 삭제된 상품은 빼므로 순위가 이어지지 않을 수 있습니다. 이름 · 가격 · 브랜드는 현재 값입니다."
    )
    ApiResponse<WeeklyRankingV1Dto.WeeklyRankingResponse> getWeeklyRanking(
        @Schema(description = "기간 시작 (UTC 월요일, yyyy-MM-dd)") LocalDate periodStart,
        @Schema(description = "개수 (1 ~ 100, 기본 10)") int limit
    );
}
