package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.BrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/brands")
public class BrandV1Controller implements BrandV1ApiSpec {

    private final BrandFacade brandFacade;

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(
        @RequesterId Long requesterId,
        @PathVariable("brandId") Long brandId
    ) {
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brandFacade.getBrand(requesterId, brandId)));
    }
}
