package com.loopers.interfaces.api.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;

public interface BrandV1ApiSpec {

    @Operation(summary = "브랜드 상세 조회", description = "고객에게 브랜드 상세 정보를 제공합니다.")
    ApiResponse<BrandV1Dto.BrandResponse> getDetail(Long brandId);
}
