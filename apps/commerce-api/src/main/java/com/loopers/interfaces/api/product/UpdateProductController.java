package com.loopers.interfaces.api.product;

import com.loopers.application.product.UpdateProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequiredArgsConstructor
public class UpdateProductController {
    private final UpdateProductFacade facade;
    @PutMapping("/api-admin/v1/products/{productId}")
    public ApiResponse<ProductDto.Admin> update(@PathVariable long productId, @RequestBody ProductDto.Update request) {
        return ApiResponse.success(ProductDto.Admin.from(facade.update(productId, request.name(), request.price())));
    }
}
