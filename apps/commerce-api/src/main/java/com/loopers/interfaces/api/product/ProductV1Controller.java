package com.loopers.interfaces.api.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductV1Controller {

    private final ProductViewQuery productViewQuery;

    @GetMapping
    public ApiResponse<ProductV1Dto.ProductPageResponse> list(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "LATEST") ProductViewQuery.Sort sort,
        @RequestParam(defaultValue = "0") PageNumber page,
        @RequestParam(defaultValue = "20") PageSize size
    ) {
        ProductViewQuery.Criteria criteria = new ProductViewQuery.Criteria(brandId, sort, page, size, userId);
        return ApiResponse.success(
            ProductV1Dto.ProductPageResponse.of(productViewQuery.findPage(criteria), page.value(), size.value()));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductResponse> get(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long productId
    ) {
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(
                productViewQuery.findAliveById(productId, userId)
                    .orElseThrow(() -> new DomainException(DomainError.PRODUCT_NOT_FOUND))));
    }
}
