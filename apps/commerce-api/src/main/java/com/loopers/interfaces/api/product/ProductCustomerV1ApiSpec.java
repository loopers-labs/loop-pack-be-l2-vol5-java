package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;

public interface ProductCustomerV1ApiSpec {

    @Operation(summary = "상품 상세 조회", description = "고객에게 상품 상세 정보를 제공합니다.")
    ApiResponse<ProductCustomerV1Dto.ProductResponse> getDetail(Long productId);
}
