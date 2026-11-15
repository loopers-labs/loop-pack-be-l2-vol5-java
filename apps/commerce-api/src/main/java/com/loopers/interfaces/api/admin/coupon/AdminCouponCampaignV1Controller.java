package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueGatePreparation;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns")
public class AdminCouponCampaignV1Controller {

    private final CouponFacade couponFacade;

    /** 행사 전 또는 발급 유입 중단·모든 실행/DB 종료 확인 후 판단 정보를 DB 기준으로 채운다. */
    @PostMapping("/{campaignId}/issue-gate")
    public ApiResponse<CouponIssueGatePreparation> prepareIssueGate(@PathVariable Long campaignId) {
        return ApiResponse.success(couponFacade.prepareIssueGate(campaignId));
    }
}
