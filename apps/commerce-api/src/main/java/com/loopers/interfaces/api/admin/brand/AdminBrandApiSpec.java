package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Brand API", description = "관리자용 브랜드 API 입니다. ROLE_ADMIN 권한이 필요합니다.")
public interface AdminBrandApiSpec {

    @Operation(summary = "브랜드 목록 조회", description = "삭제되지 않은 브랜드를 최신순으로 조회합니다.")
    ApiResponse<PageResponse<AdminBrandDto.BrandResponse>> getBrands(
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );

    @Operation(summary = "브랜드 상세 조회", description = "삭제되지 않은 브랜드를 조회합니다.")
    ApiResponse<AdminBrandDto.BrandResponse> getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        Long brandId
    );

    @Operation(summary = "브랜드 등록", description = "이름(1~100자)으로 브랜드를 등록합니다.")
    ApiResponse<AdminBrandDto.BrandResponse> register(AdminBrandDto.BrandRequest request);

    @Operation(summary = "브랜드 수정", description = "삭제되지 않은 브랜드의 이름을 수정합니다.")
    ApiResponse<AdminBrandDto.BrandResponse> update(
        @Schema(name = "브랜드 ID", description = "수정할 브랜드의 ID")
        Long brandId,
        AdminBrandDto.BrandRequest request
    );

    @Operation(summary = "브랜드 삭제", description = "삭제되지 않은 상품(재고 0 포함)이 남아 있으면 삭제할 수 없습니다.")
    ApiResponse<Object> delete(
        @Schema(name = "브랜드 ID", description = "삭제할 브랜드의 ID")
        Long brandId
    );
}
