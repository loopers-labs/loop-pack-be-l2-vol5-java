package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductQueryService;
import com.loopers.application.product.ProductListQueryService;
import com.loopers.application.product.ProductSort;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductV1Controller {

    private final ProductQueryService productQueryService;
    private final ProductListQueryService productListQueryService;

    @GetMapping
    public ApiResponse<ProductV1Dto.ProductListResponse> getProducts(
        @RequestHeader(value = "X-USER-ID", required = false) Long requesterId,
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "latest") String sort
    ) {
        return ApiResponse.success(ProductV1Dto.ProductListResponse.from(
            productListQueryService.getList(requesterId, brandId, page, size, ProductSort.from(sort))
        ));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(productQueryService.get(productId)));
    }
}
