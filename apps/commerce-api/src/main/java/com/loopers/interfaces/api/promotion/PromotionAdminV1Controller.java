package com.loopers.interfaces.api.promotion;

import com.loopers.application.promotion.PromotionFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class PromotionAdminV1Controller implements PromotionAdminV1ApiSpec {

    private final PromotionFacade promotionFacade;

    @PostMapping("/api-admin/v1/promotions")
    @Override
    public ApiResponse<PromotionV1Dto.PromotionResponse> create(
        @RequesterId Long requesterId,
        @RequestBody PromotionV1Dto.CreateRequest request
    ) {
        var info = promotionFacade.create(requesterId, request.toCommand());
        return ApiResponse.success(PromotionV1Dto.PromotionResponse.from(info));
    }
}
