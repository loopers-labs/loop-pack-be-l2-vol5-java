package com.loopers.interfaces.api.catalog;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand V1 API", description = "브랜드 고객 API")
public interface BrandV1ApiSpec {

    /** EP-01 GET /api/v1/brands/{brandId} — FR-BRAND-01 */
    @Operation(summary = "브랜드 상세 조회", description = "삭제되지 않은 브랜드 정보를 반환한다.")
    ApiResponse<BrandV1Dto.BrandResponse> getBrand(Long requesterId, Long brandId);
}
