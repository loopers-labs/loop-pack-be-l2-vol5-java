package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.ProductSort;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.PageQuery;
import com.loopers.interfaces.api.support.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    public ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        ProductSort productSort = ProductV1Dto.parseSort(sort);
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.unsorted());
        return ApiResponse.success(
            PageResponse.from(productFacade.getProducts(brandId, productSort, pageable), ProductV1Dto.ProductResponse::from)
        );
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(productFacade.getProduct(productId)));
    }
}
