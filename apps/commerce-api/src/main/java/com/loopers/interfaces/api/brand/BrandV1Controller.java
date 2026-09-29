package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brands")
@RequiredArgsConstructor
public class BrandV1Controller {

    private final BrandService brandService;

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> get(@PathVariable Long brandId) {
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brandService.get(brandId)));
    }
}
