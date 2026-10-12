package com.loopers.product.adapter.in.web;

import com.loopers.product.adapter.in.web.dto.ProductDto;
import com.loopers.product.adapter.in.web.spec.ProductApiSpec;
import com.loopers.product.application.ProductQueryService;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageQuery;
import com.loopers.support.web.PageResponse;
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
public class ProductController implements ProductApiSpec {

    private final ProductQueryService productQueryService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductDto.ProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        ProductSort productSort = ProductDto.parseSort(sort);
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.unsorted());
        return ApiResponse.success(
            PageResponse.from(productQueryService.getProducts(brandId, productSort, pageable), ProductDto.ProductResponse::from)
        );
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductDto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(ProductDto.ProductResponse.from(productQueryService.getProduct(productId)));
    }
}
