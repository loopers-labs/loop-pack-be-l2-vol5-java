package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Brand V1 API", description = "관리자용 브랜드 API 입니다.")
public interface AdminBrandV1ApiSpec {

    @Operation(summary = "브랜드 목록", description = "삭제되지 않은 브랜드를 생성 시각 역순으로 조회합니다.")
    ApiResponse<PageResponse<AdminBrandV1Dto.BrandResponse>> getBrands(
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );

    @Operation(summary = "브랜드 등록")
    ApiResponse<AdminBrandV1Dto.BrandResponse> createBrand(AdminBrandV1Dto.BrandRequest request);

    @Operation(summary = "브랜드 상세", description = "없거나 삭제된 브랜드는 BRAND_NOT_FOUND 입니다.")
    ApiResponse<AdminBrandV1Dto.BrandResponse> getBrand(@Schema(description = "브랜드 ID") Long brandId);

    @Operation(summary = "브랜드 수정", description = "이름과 설명을 모두 보냅니다 (전체 교체).")
    ApiResponse<AdminBrandV1Dto.BrandResponse> updateBrand(
        @Schema(description = "브랜드 ID") Long brandId,
        AdminBrandV1Dto.BrandRequest request
    );

    @Operation(summary = "브랜드 삭제", description = "삭제되지 않은 상품이 남은 브랜드는 BRAND_HAS_PRODUCTS 입니다.")
    ApiResponse<Object> deleteBrand(@Schema(description = "브랜드 ID") Long brandId);
}
