package com.loopers.brand.adapter.in.web.spec;

import com.loopers.brand.adapter.in.web.dto.BrandDto;
import com.loopers.support.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand V1 API", description = "고객용 브랜드 API")
public interface BrandApiSpec {

    @Operation(summary = "브랜드 상세 조회", description = "존재하며 삭제되지 않은 브랜드를 조회합니다.")
    ApiResponse<BrandDto.BrandResponse> getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        Long brandId
    );
}
