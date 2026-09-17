package com.loopers.interfaces.api.product;

import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.support.CustomerId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductV1Controller implements ProductV1ApiSpec {

    private final ProductService productService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getProducts(
        @CustomerId Long userId,
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        @RequestParam(required = false) String sort
    ) {
        PageResult<ProductQueryResult> result =
            productService.getProducts(brandId, PageCommand.of(page, size), ProductSort.from(sort));
        return ApiResponse.success(PageResponse.of(result, ProductV1Dto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductV1Dto.ProductResponse> getProduct(
        @CustomerId Long userId,
        @PathVariable Long productId
    ) {
        ProductQueryResult result = productService.getProduct(productId);
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(result));
    }
}
