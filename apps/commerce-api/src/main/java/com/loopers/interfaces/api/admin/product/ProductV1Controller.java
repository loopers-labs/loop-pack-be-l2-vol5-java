package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductV1Controller implements ProductV1ApiSpec {

    private final ProductFacade productFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<ProductV1Dto.ProductResponse> register(@RequestBody ProductV1Dto.CreateRequest request) {
        ProductInfo info = productFacade.register(request.brandId(), request.name(), request.price());
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(info));
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductV1Dto.StockResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody ProductV1Dto.StockUpdateRequest request
    ) {
        ProductInfo info = productFacade.changeStock(productId, request.quantity());
        return ApiResponse.success(ProductV1Dto.StockResponse.from(info));
    }
}
