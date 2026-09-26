package com.loopers.product.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.Requester;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductV1Controller {

    private final ProductUseCase productUseCase;

    @GetMapping
    public ApiResponse<ListResponse<ProductV1Dto.CustomerProductResponse>> getProducts(
        Requester requester,
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            productUseCase.findCustomerProductPage(brandId, toSort(sort), query.page(), query.size()),
            ProductV1Dto.CustomerProductResponse::from
        ));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.CustomerProductResponse> getProduct(
        Requester requester,
        @PathVariable Long productId
    ) {
        return ApiResponse.success(
            ProductV1Dto.CustomerProductResponse.from(productUseCase.findCustomerProduct(productId))
        );
    }

    /**
     * 정렬값이 없으면 null을 넘겨 application의 기본 정렬을 쓴다.
     */
    private static ProductSort toSort(String sort) {
        if (sort == null) {
            return null;
        }
        return switch (sort) {
            case "latest" -> ProductSort.LATEST;
            case "price_asc" -> ProductSort.PRICE_ASC;
            case "likes_desc" -> ProductSort.LIKES_DESC;
            default -> throw new CoreException(ErrorCode.INVALID_REQUEST);
        };
    }
}
