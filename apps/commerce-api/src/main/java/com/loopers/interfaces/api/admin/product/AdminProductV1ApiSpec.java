package com.loopers.interfaces.api.admin.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Product V1 API", description = "관리자용 상품 API 입니다.")
public interface AdminProductV1ApiSpec {

    @Operation(summary = "상품 목록", description = "삭제되지 않은 상품을 생성 시각 역순으로 조회합니다. 없거나 삭제된 brandId 는 BRAND_NOT_FOUND 입니다.")
    ApiResponse<PageResponse<AdminProductV1Dto.ProductResponse>> getProducts(
        @Schema(description = "브랜드 ID (선택)") Long brandId,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );

    @Operation(summary = "상품 등록", description = "재고는 0 으로 시작하며 재고 변경 API 로만 바꿉니다.")
    ApiResponse<AdminProductV1Dto.ProductResponse> createProduct(AdminProductV1Dto.CreateRequest request);

    @Operation(summary = "상품 상세", description = "없거나 삭제된 상품은 PRODUCT_NOT_FOUND 입니다.")
    ApiResponse<AdminProductV1Dto.ProductResponse> getProduct(@Schema(description = "상품 ID") Long productId);

    @Operation(summary = "상품 수정", description = "이름과 가격을 모두 보냅니다 (전체 교체). 브랜드는 바꿀 수 없습니다.")
    ApiResponse<AdminProductV1Dto.ProductResponse> updateProduct(
        @Schema(description = "상품 ID") Long productId,
        AdminProductV1Dto.UpdateRequest request
    );

    @Operation(summary = "상품 삭제")
    ApiResponse<Object> deleteProduct(@Schema(description = "상품 ID") Long productId);

    @Operation(summary = "재고 변경", description = "증감량이 아니라 최종 수량을 설정합니다.")
    ApiResponse<AdminProductV1Dto.ProductResponse> changeStock(
        @Schema(description = "상품 ID") Long productId,
        AdminProductV1Dto.StockRequest request
    );
}
