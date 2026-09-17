package com.loopers.interfaces.api.catalog;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Brand Admin V1 API", description = "브랜드 관리자 API")
public interface BrandAdminV1ApiSpec {

    /** EP-14 GET /api-admin/v1/brands — FR-ADMIN-BRAND-01 */
    @Operation(summary = "브랜드 목록", description = "삭제 포함 전체 브랜드를 최신순 페이지로 반환한다.")
    ApiResponse<PageResponse<BrandAdminV1Dto.BrandAdminResponse>> listBrands(Long requesterId, Integer page, Integer size);

    /** EP-15 POST /api-admin/v1/brands — FR-ADMIN-BRAND-02 */
    @Operation(summary = "브랜드 생성")
    ApiResponse<BrandAdminV1Dto.BrandAdminResponse> createBrand(Long requesterId, BrandAdminV1Dto.BrandRequest request);

    /** EP-16 GET /api-admin/v1/brands/{brandId} — FR-ADMIN-BRAND-03 */
    @Operation(summary = "브랜드 상세", description = "삭제 여부 무관하게 반환한다.")
    ApiResponse<BrandAdminV1Dto.BrandAdminResponse> getBrand(Long requesterId, Long brandId);

    /** EP-17 PUT /api-admin/v1/brands/{brandId} — FR-ADMIN-BRAND-04 */
    @Operation(summary = "브랜드 수정")
    ApiResponse<BrandAdminV1Dto.BrandAdminResponse> updateBrand(Long requesterId, Long brandId, BrandAdminV1Dto.BrandRequest request);

    /** EP-18 DELETE /api-admin/v1/brands/{brandId} — FR-ADMIN-BRAND-05 */
    @Operation(summary = "브랜드 삭제", description = "삭제되지 않은 상품이 연결되어 있으면 거절한다.")
    ApiResponse<Object> deleteBrand(Long requesterId, Long brandId);
}
