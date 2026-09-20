package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
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
    public ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> listProducts(
        @RequesterId Long requesterId,
        @RequestParam(value = "sort", required = false) String sort,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size
    ) {
        var result = productFacade.listProducts(requesterId, sort, PageQuery.of(page, size));
        return ApiResponse.success(PageResponse.from(result, ProductV1Dto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductV1Dto.ProductResponse> getProduct(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId
    ) {
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(productFacade.getProduct(requesterId, productId)));
    }
}
