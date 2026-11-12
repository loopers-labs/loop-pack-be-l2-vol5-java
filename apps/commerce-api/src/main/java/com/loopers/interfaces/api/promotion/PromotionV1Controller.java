package com.loopers.interfaces.api.promotion;

import com.loopers.application.promotion.PromotionFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
@RestController
public class PromotionV1Controller implements PromotionV1ApiSpec {

    private final PromotionFacade promotionFacade;

    @PostMapping("/api/v1/promotions/{promotionId}/issues")
    @Override
    public CompletableFuture<ApiResponse<PromotionV1Dto.IssuedCouponResponse>> issue(
        @RequesterId Long requesterId,
        @PathVariable("promotionId") Long promotionId
    ) {
        return promotionFacade.issue(requesterId, promotionId)
            .thenApply(info -> ApiResponse.success(PromotionV1Dto.IssuedCouponResponse.from(info)));
    }
}
