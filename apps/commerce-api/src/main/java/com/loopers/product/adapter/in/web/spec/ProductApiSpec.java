package com.loopers.product.adapter.in.web.spec;

import com.loopers.product.adapter.in.web.dto.ProductDto;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "고객용 상품 API")
public interface ProductApiSpec {

    @Operation(summary = "상품 목록 조회", description = "브랜드 필터, latest·price_asc·likes_desc 정렬, 페이지 조회. 동률은 최신 등록순.")
    ApiResponse<PageResponse<ProductDto.ProductResponse>> getProducts(Long brandId, String sort, Integer page, Integer size);

    @Operation(summary = "상품 상세 조회", description = "브랜드 정보와 좋아요 수를 포함합니다.")
    ApiResponse<ProductDto.ProductResponse> getProduct(Long productId);
}
