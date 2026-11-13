package com.loopers.interfaces.api.coupon.controller;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.query.CouponQueryDao;
import com.loopers.application.coupon.query.CouponView;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.usecase.IssueCouponUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponController {
    private final IssueCouponUseCase issuance;
    private final CouponQueryDao coupons;

    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    public ResponseEntity<ApiResponse<CouponView>> issue(@XUserId long userId, @PathVariable long campaignId) {
        RequestInputValidator.requirePositiveId(campaignId, "캠페인 ID");
        CouponIssueResult result = issuance.execute(new CouponIssueCommand(campaignId, userId));
        CouponView view = new CouponView(result.couponId(), result.campaignId(), result.issuedAt(),
            result.benefitDescription(), result.discountAmount());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.success(view));
    }

    @GetMapping("/api/v1/coupons")
    public ApiResponse<List<CouponView>> find(@XUserId long userId) {
        return ApiResponse.success(coupons.findCoupons(userId));
    }
}
