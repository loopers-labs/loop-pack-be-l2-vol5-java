package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product API", description = "고객용 상품 API 입니다.")
public interface ProductApiSpec {

    @Operation(
        summary = "상품 목록 조회",
        description = "삭제되지 않은 상품을 브랜드 필터·정렬(latest, price_asc, likes_desc)·페이지로 조회합니다."
    )
    ApiResponse<PageResponse<ProductDto.ProductResponse>> getProducts(
        @Schema(name = "브랜드 ID", description = "필터할 브랜드의 ID (선택)")
        Long brandId,
        @Schema(name = "정렬", description = "latest, price_asc, likes_desc (기본 latest)")
        String sort,
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );

    @Operation(
        summary = "상품 상세 조회",
        description = "삭제되지 않은 상품을 브랜드 정보와 좋아요 수와 함께 조회합니다."
    )
    ApiResponse<ProductDto.ProductResponse> getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        Long productId
    );
}
