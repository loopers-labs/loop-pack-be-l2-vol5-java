package com.loopers.interfaces.api.product;

import com.loopers.application.product.SetStockProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequiredArgsConstructor
public class SetStockProductController {
    private final SetStockProductFacade facade;
    @PutMapping("/api-admin/v1/products/{productId}/stock")
    public ApiResponse<ProductDto.StockResponse> set(@PathVariable long productId, @RequestBody ProductDto.Stock request) {
        var info = facade.set(productId, request.stock());
        return ApiResponse.success(new ProductDto.StockResponse(info.productId(), info.stock()));
    }
}
