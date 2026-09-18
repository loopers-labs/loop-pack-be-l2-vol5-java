package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
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

    private final ProductFacade productFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductV1Dto.ProductSummaryResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "latest") String sort,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            productFacade.getProducts(brandId, ProductSortParam.toSort(sort), PageQuery.of(page, size)),
            ProductV1Dto.ProductSummaryResponse::from
        ));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductV1Dto.ProductDetailResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductV1Dto.ProductDetailResponse.from(productFacade.getProduct(productId)));
    }
}
