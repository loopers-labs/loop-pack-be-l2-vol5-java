package com.loopers.brand.adapter.in.web;

import com.loopers.brand.adapter.in.web.dto.BrandDto;
import com.loopers.brand.adapter.in.web.spec.BrandApiSpec;
import com.loopers.brand.application.BrandQueryService;
import com.loopers.support.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/brands")
public class BrandController implements BrandApiSpec {

    private final BrandQueryService brandQueryService;

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandDto.BrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(BrandDto.BrandResponse.from(brandQueryService.getBrand(brandId)));
    }
}
