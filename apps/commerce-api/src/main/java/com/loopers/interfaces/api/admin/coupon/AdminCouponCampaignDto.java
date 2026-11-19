package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.dto.CouponCampaignInfo;
import com.loopers.application.coupon.dto.CouponReconcileInfo;
import jakarta.validation.constraints.NotNull;

public class AdminCouponCampaignDto {

    // 누락은 요청 형식 오류라 여기서 거절한다. 금액·한도의 범위는 CouponCampaign 이 판단한다.
    public record PrepareRequest(@NotNull String name, @NotNull Long discountAmount, @NotNull Integer totalQuota) {}

    public record CampaignResponse(Long id, String name, long discountAmount, int totalQuota, int issuedCount, boolean active) {
        public static CampaignResponse from(CouponCampaignInfo info) {
            return new CampaignResponse(
                info.id(), info.name(), info.discountAmount(), info.totalQuota(), info.issuedCount(), info.active());
        }
    }

    public record ReconcileResponse(int issued, int recorded, int remaining) {
        public static ReconcileResponse from(CouponReconcileInfo info) {
            return new ReconcileResponse(info.issued(), info.recorded(), info.remaining());
        }
    }
}
