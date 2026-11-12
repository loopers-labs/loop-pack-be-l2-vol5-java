package com.loopers.coupon.adapter.in.web.spec;

import com.loopers.coupon.adapter.in.web.dto.CouponCampaignAdminDto;
import com.loopers.support.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Coupon Campaign Admin V1 API", description = "관리자용 선착순 쿠폰 캠페인 API")
public interface CouponCampaignAdminApiSpec {

    @Operation(summary = "캠페인 생성", description = "캠페인과 쿠폰들(쿠폰팩)을 한 번에 만듭니다. 줄 길이 제한이 없으면 당첨자 수 × 10입니다.")
    ApiResponse<CouponCampaignAdminDto.CampaignResponse> create(CouponCampaignAdminDto.CreateRequest request);

    @Operation(summary = "캠페인 조회", description = "당첨자 수·발급 수·기간과 담긴 쿠폰들을 봅니다.")
    ApiResponse<CouponCampaignAdminDto.CampaignResponse> get(Long campaignId);
}
