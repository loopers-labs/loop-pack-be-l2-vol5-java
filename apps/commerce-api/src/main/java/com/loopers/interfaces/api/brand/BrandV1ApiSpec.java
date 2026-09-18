package com.loopers.interfaces.api.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand V1 API", description = "브랜드 조회 API 입니다. 요청자를 식별하지 않습니다.")
public interface BrandV1ApiSpec {

    @Operation(summary = "브랜드 상세", description = "없거나 삭제된 브랜드는 BRAND_NOT_FOUND 입니다.")
    ApiResponse<BrandV1Dto.BrandResponse> getBrand(@Schema(description = "브랜드 ID") Long brandId);
}
