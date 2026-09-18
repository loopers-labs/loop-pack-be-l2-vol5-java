package com.loopers.interfaces.api.product;

import com.loopers.application.product.DeleteProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
public class DeleteProductController {
    private final DeleteProductFacade facade;
    @DeleteMapping("/api-admin/v1/products/{productId}")
    public ApiResponse<Object> delete(@PathVariable long productId) {
        facade.delete(productId);
        return ApiResponse.success();
    }
}
