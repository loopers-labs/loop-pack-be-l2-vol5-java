package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Coupon V1 API", description = "선착순 쿠폰 API 입니다. X-USER-ID 헤더로 요청자를 식별합니다.")
public interface CouponV1ApiSpec {

    @Operation(
        summary = "쿠폰 발급 접수",
        description = "접수되면 202(WAITING · RESERVE)이고 발급은 나중에 확정됩니다. 발급 · 품절 · 최종 실패는 200 과 본문 상태입니다. "
            + "같은 사용자가 다시 요청하면 기존 상태를 돌려줍니다. Retry-After 는 다시 확인할 시각의 안내일 뿐 발급 약속이 아닙니다."
    )
    ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> requestIssue(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "캠페인 ID") Long campaignId
    );

    @Operation(
        summary = "내 발급 결과 조회",
        description = "접수 기록이 없으면 COUPON_ISSUE_NOT_FOUND 입니다. 지금 확인할 수 없으면 503 입니다."
    )
    ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> getMyIssue(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "캠페인 ID") Long campaignId
    );

    @Operation(summary = "내 보유 쿠폰 목록", description = "최근 발급 순입니다. 본인 쿠폰만 돌려줍니다.")
    ApiResponse<PageResponse<CouponV1Dto.CouponResponse>> getMyCoupons(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );
}
