package com.loopers.interfaces.api.admin.coupon;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Coupon Campaign V1 API", description = "쿠폰 캠페인 준비·수용 재개·종료·마감 보정 관리자 API 입니다.")
public interface AdminCouponCampaignApiSpec {

    @Operation(summary = "캠페인 준비", description = "캠페인을 만들고 수용을 닫힌 채로 준비합니다. 발급은 열기 뒤에 시작됩니다.")
    ApiResponse<AdminCouponCampaignDto.CampaignResponse> prepare(AdminCouponCampaignDto.PrepareRequest request);

    @Operation(
        summary = "수용 열기·재개",
        description = "DB 의 쿠폰과 당첨 기록으로 당첨자 명단을 다시 만들고, 이 인스턴스가 남긴 자리만 정리한 뒤 엽니다. "
            + "진행 중인 발급이 있으면 409 로 거절하고 닫힌 상태를 유지합니다."
    )
    ApiResponse<Object> open(Long campaignId);

    @Operation(summary = "캠페인 종료", description = "신규 발급을 끝냅니다. 이미 발급된 쿠폰과 그 조회는 유지됩니다.")
    ApiResponse<Object> close(Long campaignId);

    @Operation(
        summary = "마감 보정",
        description = "쿠폰이 기록되지 않은 당첨자를 실행 자리 안에서 저장합니다. 자리가 없으면 멈추고 남은 수를 돌려줍니다."
    )
    ApiResponse<AdminCouponCampaignDto.ReconcileResponse> reconcile(Long campaignId);
}
