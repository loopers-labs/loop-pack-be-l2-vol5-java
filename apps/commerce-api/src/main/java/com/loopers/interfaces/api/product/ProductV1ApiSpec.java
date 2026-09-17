package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product V1 API", description = "고객 상품 API")
public interface ProductV1ApiSpec {

    ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getProducts(
        Long userId, Long brandId, Integer page, Integer size, String sort);

    ApiResponse<ProductV1Dto.ProductResponse> getProduct(Long userId, Long productId);
}
