package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand Admin V1 API", description = "관리자용 브랜드 API")
public interface BrandAdminV1ApiSpec {

    @Operation(summary = "브랜드 목록 조회", description = "삭제되지 않은 브랜드를 최신 등록순으로 조회합니다.")
    ApiResponse<PageResponse<BrandAdminV1Dto.BrandResponse>> getBrands(Integer page, Integer size);

    @Operation(summary = "브랜드 등록")
    ApiResponse<BrandAdminV1Dto.BrandResponse> createBrand(BrandAdminV1Dto.CreateRequest request);

    @Operation(summary = "브랜드 상세 조회")
    ApiResponse<BrandAdminV1Dto.BrandResponse> getBrand(Long brandId);

    @Operation(summary = "브랜드 수정")
    ApiResponse<BrandAdminV1Dto.BrandResponse> updateBrand(Long brandId, BrandAdminV1Dto.UpdateRequest request);

    @Operation(summary = "브랜드 삭제", description = "삭제되지 않은 상품(재고 0 포함)이 연결된 브랜드는 삭제할 수 없습니다.")
    ApiResponse<Object> deleteBrand(Long brandId);
}
