package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.AdminCouponCampaignFacade;
import com.loopers.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns")
public class AdminCouponCampaignController implements AdminCouponCampaignApiSpec {

    private final AdminCouponCampaignFacade adminCouponCampaignFacade;

    @PostMapping
    @Override
    public ApiResponse<AdminCouponCampaignDto.CampaignResponse> prepare(@Valid @RequestBody AdminCouponCampaignDto.PrepareRequest request) {
        return ApiResponse.success(AdminCouponCampaignDto.CampaignResponse.from(
            adminCouponCampaignFacade.prepare(request.name(), request.discountAmount(), request.totalQuota())));
    }

    @PostMapping("/{campaignId}/open")
    @Override
    public ApiResponse<Object> open(@PathVariable Long campaignId) {
        adminCouponCampaignFacade.open(campaignId);
        return ApiResponse.success();
    }

    @PostMapping("/{campaignId}/close")
    @Override
    public ApiResponse<Object> close(@PathVariable Long campaignId) {
        adminCouponCampaignFacade.close(campaignId);
        return ApiResponse.success();
    }

    @PostMapping("/{campaignId}/reconcile")
    @Override
    public ApiResponse<AdminCouponCampaignDto.ReconcileResponse> reconcile(@PathVariable Long campaignId) {
        return ApiResponse.success(AdminCouponCampaignDto.ReconcileResponse.from(adminCouponCampaignFacade.reconcile(campaignId)));
    }
}
