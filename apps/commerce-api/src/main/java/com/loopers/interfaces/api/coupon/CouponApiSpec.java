package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.support.paging.PageRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Coupon V1 API", description = "선착순 쿠폰 발급과 보유 쿠폰 조회 API 입니다.")
public interface CouponApiSpec {

    @Operation(
        summary = "쿠폰 발급",
        description = "요청자에게 캠페인 쿠폰을 발급합니다. 처음 발급하면 201, 이미 받은 쿠폰이면 같은 couponId 로 200, "
            + "당첨됐지만 저장이 늦어지면 202(당첨 기록은 저장됨)입니다. 품절·종료·처리 중은 409, "
            + "수용 상태를 확인할 수 없으면 503 이며, 409·503 은 발급 성공이나 나중 발급을 뜻하지 않습니다."
    )
    @Parameter(name = "X-USER-ID", in = ParameterIn.HEADER, required = true, description = "요청자(사용자) ID")
    ResponseEntity<ApiResponse<CouponDto.IssueResponse>> issue(
        @Parameter(hidden = true)
        Long userId,
        Long campaignId
    );

    @Operation(
        summary = "보유 쿠폰 목록",
        description = "요청자가 발급받은 쿠폰을 발급 시각 내림차순, 같으면 couponId 내림차순으로 조회합니다."
    )
    @Parameter(name = "X-USER-ID", in = ParameterIn.HEADER, required = true, description = "요청자(사용자) ID")
    ApiResponse<PageResponse<CouponDto.CouponResponse>> getMyList(
        @Parameter(hidden = true)
        Long userId,
        @Parameter(description = PageRequests.PAGE_PARAM_DESCRIPTION)
        int page,
        @Parameter(description = PageRequests.SIZE_PARAM_DESCRIPTION)
        int size
    );
}
