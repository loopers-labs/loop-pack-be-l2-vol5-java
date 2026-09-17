package com.loopers.interfaces.api.product;

import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductCustomerV1Controller implements ProductCustomerV1ApiSpec {

    private final ProductFacade productFacade;

    @GetMapping
    public ApiResponse<List<ProductCustomerV1Dto.ProductResponse>> getList(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "latest") String sort
    ) {
        return ApiResponse.success(productFacade.getCustomerList(brandId, page, size, sort).stream()
            .map(ProductCustomerV1Dto.ProductResponse::from)
            .toList());
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductCustomerV1Dto.ProductResponse> getDetail(@PathVariable Long productId) {
        CustomerProductInfo info = productFacade.getCustomerDetail(productId);
        return ApiResponse.success(ProductCustomerV1Dto.ProductResponse.from(info));
    }
}
