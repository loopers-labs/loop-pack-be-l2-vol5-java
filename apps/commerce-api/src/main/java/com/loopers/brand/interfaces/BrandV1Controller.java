package com.loopers.brand.interfaces;

import com.loopers.brand.application.BrandUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.Requester;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/brands")
public class BrandV1Controller {

    private final BrandUseCase brandUseCase;

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.CustomerBrandResponse> getBrand(
        Requester requester,
        @PathVariable Long brandId
    ) {
        return ApiResponse.success(BrandV1Dto.CustomerBrandResponse.from(brandUseCase.findActive(brandId)));
    }
}
