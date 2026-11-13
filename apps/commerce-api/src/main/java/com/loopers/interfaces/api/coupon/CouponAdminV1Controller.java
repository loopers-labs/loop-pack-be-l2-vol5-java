package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/coupon-campaigns")
@RequiredArgsConstructor
public class CouponAdminV1Controller {

    private final CouponFacade couponFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponAdminV1Dto.CampaignResponse> open(@RequestBody CouponAdminV1Dto.OpenRequest request) {
        return ApiResponse.success(CouponAdminV1Dto.CampaignResponse.from(couponFacade.openCampaign(
            request.toName(), request.toDiscountAmount(), request.toTotalQuota(), request.active())));
    }

    @PostMapping("/{campaignId}/recover")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CouponAdminV1Dto.RecoveryResponse> recover(@PathVariable Long campaignId) {
        couponFacade.startRecovery(campaignId);
        return ApiResponse.success(new CouponAdminV1Dto.RecoveryResponse(campaignId));
    }
}
