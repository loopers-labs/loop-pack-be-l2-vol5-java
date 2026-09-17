package com.loopers.interfaces.api.catalog;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "상품 고객 API")
public interface ProductV1ApiSpec {

    /** EP-02 GET /api/v1/products — FR-PRODUCT-01 */
    @Operation(summary = "상품 목록 조회", description = "삭제되지 않은 상품을 sort(latest|price_asc|likes_desc) 하나로 정렬해 페이지로 반환한다.")
    ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> listProducts(Long requesterId, String sort, Integer page, Integer size);

    /** EP-03 GET /api/v1/products/{productId} — FR-PRODUCT-02 */
    @Operation(summary = "상품 상세 조회", description = "삭제되지 않은 상품 정보 + 브랜드 정보 + 좋아요 수를 반환한다.")
    ApiResponse<ProductV1Dto.ProductResponse> getProduct(Long requesterId, Long productId);
}
