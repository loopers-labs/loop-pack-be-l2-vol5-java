package com.loopers.interfaces.api.product;

import com.loopers.application.product.GetAdminProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetAdminProductController {
    private final GetAdminProductFacade facade;
    @GetMapping("/api-admin/v1/products/{productId}")
    public ApiResponse<ProductDto.Admin> get(@PathVariable long productId) {
        return ApiResponse.success(ProductDto.Admin.from(facade.admin(productId)));
    }
    @GetMapping("/api-admin/v1/products")
    public ApiResponse<PageResponse<ProductDto.Admin>> list(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.from(facade.list(page, size).map(ProductDto.Admin::from)));
    }
}
