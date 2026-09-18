package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "상품 조회 API 입니다. 요청자를 식별하지 않습니다.")
public interface ProductV1ApiSpec {

    @Operation(
        summary = "상품 목록",
        description = "삭제되지 않은 상품을 좋아요 수와 함께 조회합니다. 없는 brandId 는 빈 목록입니다. 동률은 식별자 역순입니다."
    )
    ApiResponse<PageResponse<ProductV1Dto.ProductSummaryResponse>> getProducts(
        @Schema(description = "브랜드 ID (선택)") Long brandId,
        @Schema(description = "정렬: latest(기본), price_asc, likes_desc") String sort,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );

    @Operation(summary = "상품 상세", description = "없거나 삭제된 상품은 PRODUCT_NOT_FOUND 입니다.")
    ApiResponse<ProductV1Dto.ProductDetailResponse> getProduct(@Schema(description = "상품 ID") Long productId);
}
