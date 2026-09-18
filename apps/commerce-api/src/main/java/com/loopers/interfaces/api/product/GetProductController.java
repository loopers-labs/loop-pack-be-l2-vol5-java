package com.loopers.interfaces.api.product;

import com.loopers.application.product.GetProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetProductController {
    private final GetProductFacade facade;
    @GetMapping("/api/v1/products/{productId}")
    public ApiResponse<ProductDto.Customer> get(@PathVariable long productId) {
        return ApiResponse.success(ProductDto.Customer.from(facade.get(productId)));
    }
    @GetMapping("/api/v1/products")
    public ApiResponse<PageResponse<ProductDto.Customer>> list(@RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "latest") String sort) {
        return ApiResponse.success(PageResponse.from(facade.list(brandId, page, size, sort).map(ProductDto.Customer::from)));
    }
}
