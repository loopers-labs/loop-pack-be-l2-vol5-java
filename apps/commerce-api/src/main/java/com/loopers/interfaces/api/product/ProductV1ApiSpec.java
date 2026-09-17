package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "고객용 상품 API")
public interface ProductV1ApiSpec {

    @Operation(summary = "상품 목록 조회", description = "브랜드 필터, latest·price_asc·likes_desc 정렬, 페이지 조회. 동률은 최신 등록순.")
    ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getProducts(Long brandId, String sort, Integer page, Integer size);

    @Operation(summary = "상품 상세 조회", description = "브랜드 정보와 좋아요 수를 포함합니다.")
    ApiResponse<ProductV1Dto.ProductResponse> getProduct(Long productId);
}
