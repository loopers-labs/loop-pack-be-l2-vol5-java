package com.loopers.interfaces.api.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand Admin V1 API", description = "Loopers 관리자 브랜드 API 입니다.")
public interface BrandAdminV1ApiSpec {

    @Operation(summary = "브랜드 목록 조회", description = "삭제되지 않은 전체 브랜드 목록을 조회합니다.")
    ApiResponse<BrandAdminV1Dto.BrandListResponse> getBrands();

    @Operation(summary = "브랜드 상세 조회", description = "ID로 브랜드를 조회합니다.")
    ApiResponse<BrandAdminV1Dto.BrandResponse> getBrand(
        @Schema(name = "브랜드 ID") Long brandId
    );

    @Operation(
        summary = "브랜드 생성",
        description = "새 브랜드를 등록합니다."
    )
    ApiResponse<BrandAdminV1Dto.BrandResponse> createBrand(
        @Schema(name = "브랜드 생성 요청", description = "이름·설명·카테고리")
        BrandAdminV1Dto.CreateRequest request
    );

    @Operation(
        summary = "브랜드 수정",
        description = "브랜드의 이름·설명·카테고리를 수정합니다."
    )
    ApiResponse<BrandAdminV1Dto.BrandResponse> updateBrand(
        @Schema(name = "브랜드 ID", description = "수정할 브랜드의 ID")
        Long brandId,
        @Schema(name = "브랜드 수정 요청", description = "이름·설명·카테고리")
        BrandAdminV1Dto.UpdateRequest request
    );

    @Operation(
        summary = "브랜드 삭제",
        description = "브랜드를 삭제(soft delete)합니다. 삭제되지 않은 상품이 남아있으면(재고 0 포함) 거절됩니다."
    )
    ApiResponse<Object> deleteBrand(
        @Schema(name = "브랜드 ID", description = "삭제할 브랜드의 ID")
        Long brandId
    );
}
