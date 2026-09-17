package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
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
public class ProductController implements ProductApiSpec {

    private final ProductFacade productFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductDto.ProductResponse>> getProducts(
        @RequestParam(value = "brandId", required = false) Long brandId,
        @RequestParam(value = "sort", defaultValue = "latest") String sort,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(productFacade.getProducts(brandId, sort, page, size), ProductDto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductDto.ProductResponse> getProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(ProductDto.ProductResponse.from(productFacade.getProduct(productId)));
    }
}
