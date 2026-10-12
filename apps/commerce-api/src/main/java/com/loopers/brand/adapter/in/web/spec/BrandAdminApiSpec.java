package com.loopers.brand.adapter.in.web.spec;

import com.loopers.brand.adapter.in.web.dto.BrandAdminDto;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand Admin V1 API", description = "관리자용 브랜드 API")
public interface BrandAdminApiSpec {

    @Operation(summary = "브랜드 목록 조회", description = "삭제되지 않은 브랜드를 최신 등록순으로 조회합니다.")
    ApiResponse<PageResponse<BrandAdminDto.BrandResponse>> getBrands(Integer page, Integer size);

    @Operation(summary = "브랜드 등록")
    ApiResponse<BrandAdminDto.BrandResponse> createBrand(BrandAdminDto.CreateRequest request);

    @Operation(summary = "브랜드 상세 조회")
    ApiResponse<BrandAdminDto.BrandResponse> getBrand(Long brandId);

    @Operation(summary = "브랜드 수정")
    ApiResponse<BrandAdminDto.BrandResponse> updateBrand(Long brandId, BrandAdminDto.UpdateRequest request);

    @Operation(summary = "브랜드 삭제", description = "삭제되지 않은 상품(재고 0 포함)이 연결된 브랜드는 삭제할 수 없습니다.")
    ApiResponse<Object> deleteBrand(Long brandId);
}
