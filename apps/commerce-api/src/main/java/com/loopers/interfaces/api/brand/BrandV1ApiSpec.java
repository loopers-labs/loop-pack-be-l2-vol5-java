package com.loopers.interfaces.api.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand V1 API", description = "고객 브랜드 API")
public interface BrandV1ApiSpec {

    ApiResponse<BrandV1Dto.BrandResponse> getBrand(Long userId, Long brandId);
}
