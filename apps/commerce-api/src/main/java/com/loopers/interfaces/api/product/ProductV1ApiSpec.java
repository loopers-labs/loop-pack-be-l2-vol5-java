package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "Loopers 상품 API 입니다.")
public interface ProductV1ApiSpec {

    @Operation(
        summary = "상품 목록 조회",
        description = "브랜드 필터·정렬(latest/price_asc/likes_desc)·페이지네이션을 지원한다. "
            + "브랜드 정보와 좋아요 수를 함께 반환한다."
    )
    ApiResponse<ProductV1Dto.ProductListResponse> getProducts(
        @Schema(name = "브랜드 ID", description = "지정하면 해당 브랜드의 상품만 조회") Long brandId,
        @Schema(name = "정렬", description = "latest(기본) | price_asc | likes_desc") String sort,
        @Schema(name = "페이지", description = "0부터 시작, 기본 0") int page,
        @Schema(name = "페이지 크기", description = "기본 20") int size
    );

    @Operation(
        summary = "상품 상세 조회",
        description = "브랜드 정보와 좋아요 수를 함께 반환한다."
    )
    ApiResponse<ProductV1Dto.ProductResponse> getProduct(
        @Schema(name = "상품 ID") Long productId
    );
}
