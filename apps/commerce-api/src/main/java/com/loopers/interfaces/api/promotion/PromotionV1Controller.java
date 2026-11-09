package com.loopers.interfaces.api.promotion;

import com.loopers.application.promotion.PromotionFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class PromotionV1Controller implements PromotionV1ApiSpec {

    private final PromotionFacade promotionFacade;

    @PostMapping("/api/v1/promotions/{promotionId}/issues")
    @Override
    public ApiResponse<PromotionV1Dto.IssuedCouponResponse> issue(
        @RequesterId Long requesterId,
        @PathVariable("promotionId") Long promotionId
    ) {
        var info = promotionFacade.issue(requesterId, promotionId);
        return ApiResponse.success(PromotionV1Dto.IssuedCouponResponse.from(info));
    }
}
