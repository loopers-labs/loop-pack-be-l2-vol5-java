package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductQueryService;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductSort;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/products", produces = "application/json")
public class ProductController {
    private final ProductQueryService queries;

    public ProductController(ProductQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductInfo> detail(@PathVariable String productId) {
        return ApiResponse.success(queries.getDetail(StrictInput.id(productId)));
    }

    @GetMapping
    public ApiResponse<PageResult<ProductInfo>> list(
        @RequestParam(required = false) String brandId, @RequestParam(required = false) String page,
        @RequestParam(required = false) String size, @RequestParam(required = false) String sort) {
        return ApiResponse.success(queries.getList(StrictInput.optionalId(brandId), StrictInput.page(page),
            StrictInput.size(size), sort(sort)));
    }

    static ProductSort sort(String value) {
        if (value == null) {
            return ProductSort.LATEST;
        }
        return switch (value) {
            case "latest" -> ProductSort.LATEST;
            case "price_asc" -> ProductSort.PRICE_ASC;
            case "likes_desc" -> ProductSort.LIKES_DESC;
            default -> throw new InvalidRequestException();
        };
    }
}
