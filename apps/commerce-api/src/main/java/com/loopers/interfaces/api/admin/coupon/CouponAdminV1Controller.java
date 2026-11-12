package com.loopers.interfaces.api.admin.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns")
public class CouponAdminV1Controller {

    private final CouponFacade couponFacade;

    @PostMapping
    public ApiResponse<CouponAdminV1Dto.CampaignResponse> createCampaign(
        @RequestBody CouponAdminV1Dto.CreateCampaignRequest request
    ) {
        return ApiResponse.success(CouponAdminV1Dto.CampaignResponse.from(
            couponFacade.createCampaign(request.name(), request.totalQuota(), request.startAt(), request.endAt())
        ));
    }

    @PostMapping("/{campaignId}/admission/open")
    public ApiResponse<CouponAdminV1Dto.AdmissionResponse> openAdmission(
        @PathVariable long campaignId
    ) {
        return ApiResponse.success(CouponAdminV1Dto.AdmissionResponse.from(
            couponFacade.openGate(campaignId)
        ));
    }

    @PostMapping("/{campaignId}/admission/close")
    public ApiResponse<CouponAdminV1Dto.AdmissionResponse> closeAdmission(
        @PathVariable long campaignId
    ) {
        return ApiResponse.success(CouponAdminV1Dto.AdmissionResponse.from(
            couponFacade.closeGate(campaignId)
        ));
    }

    @GetMapping("/{campaignId}/admission")
    public ApiResponse<CouponAdminV1Dto.AdmissionResponse> getAdmission(
        @PathVariable long campaignId
    ) {
        return ApiResponse.success(CouponAdminV1Dto.AdmissionResponse.from(
            couponFacade.getAdmissionStatus(campaignId)
        ));
    }
}
