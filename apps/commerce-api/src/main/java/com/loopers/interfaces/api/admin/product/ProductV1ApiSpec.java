package com.loopers.interfaces.api.admin.product;

import com.loopers.interfaces.api.ApiResponse;

public interface ProductV1ApiSpec {

    ApiResponse<ProductV1Dto.ProductResponse> register(ProductV1Dto.CreateRequest request);
}
