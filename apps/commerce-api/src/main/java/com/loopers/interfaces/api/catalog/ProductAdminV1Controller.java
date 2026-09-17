package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminV1Controller implements ProductAdminV1ApiSpec {

    private final ProductFacade productFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductAdminV1Dto.ProductAdminResponse>> listProducts(
        @RequesterId Long requesterId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size
    ) {
        var result = productFacade.listProductsForAdmin(requesterId, PageQuery.of(page, size));
        return ApiResponse.success(PageResponse.from(result, ProductAdminV1Dto.ProductAdminResponse::from));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductAdminResponse> createProduct(
        @RequesterId Long requesterId,
        @RequestBody ProductAdminV1Dto.CreateRequest request
    ) {
        var info = productFacade.createProduct(requesterId, request.brandId(), request.name(), request.price(), request.stock());
        return ApiResponse.success(ProductAdminV1Dto.ProductAdminResponse.from(info));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductAdminResponse> getProduct(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId
    ) {
        return ApiResponse.success(
            ProductAdminV1Dto.ProductAdminResponse.from(productFacade.getProductForAdmin(requesterId, productId)));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductAdminResponse> updateProduct(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId,
        @RequestBody ProductAdminV1Dto.UpdateRequest request
    ) {
        var info = productFacade.updateProduct(requesterId, productId, request.name(), request.price());
        return ApiResponse.success(ProductAdminV1Dto.ProductAdminResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> deleteProduct(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId
    ) {
        productFacade.deleteProduct(requesterId, productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductAdminV1Dto.StockResponse> updateStock(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        return ApiResponse.success(
            ProductAdminV1Dto.StockResponse.from(productFacade.updateStock(requesterId, productId, request.stock())));
    }
}
