package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/brands")
public class BrandV1Controller implements BrandV1ApiSpec {

    private final BrandService brandService;

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(
        @CustomerId Long userId,
        @PathVariable Long brandId
    ) {
        BrandModel brand = brandService.getBrand(brandId);
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }
}
