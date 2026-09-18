package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.AdminProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductV1Controller implements AdminProductV1ApiSpec {

    private final AdminProductFacade adminProductFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminProductV1Dto.ProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            adminProductFacade.getProducts(brandId, PageQuery.of(page, size)),
            AdminProductV1Dto.ProductResponse::from
        ));
    }

    @PostMapping
    @Override
    public ApiResponse<AdminProductV1Dto.ProductResponse> createProduct(@RequestBody AdminProductV1Dto.CreateRequest request) {
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(
            adminProductFacade.createProduct(request.brandId(), request.name(), request.price())
        ));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<AdminProductV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(adminProductFacade.getProduct(productId)));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<AdminProductV1Dto.ProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody AdminProductV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(
            adminProductFacade.updateProduct(productId, request.name(), request.price())
        ));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> deleteProduct(@PathVariable Long productId) {
        adminProductFacade.deleteProduct(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<AdminProductV1Dto.ProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody AdminProductV1Dto.StockRequest request
    ) {
        return ApiResponse.success(AdminProductV1Dto.ProductResponse.from(
            adminProductFacade.changeStock(productId, request.stock())
        ));
    }
}
