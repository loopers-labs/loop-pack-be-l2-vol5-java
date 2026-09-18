package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
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

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductV1Controller {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductFacade productFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductV1Dto.ProductResponse> createProduct(
        @RequestBody AdminProductV1Dto.CreateRequest request
    ) {
        ProductInfo info = productFacade.createProduct(request.brandId(), request.name(), request.price());
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(info));
    }

    @GetMapping
    public ApiResponse<List<ProductV1Dto.ProductResponse>> getProducts(
        @RequestParam(value = "brandId", required = false) Long brandId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size
    ) {
        List<ProductV1Dto.ProductResponse> response =
            productFacade.getProductsForAdmin(brandId, page, size).stream()
                .map(ProductV1Dto.ProductResponse::from)
                .toList();

        return ApiResponse.success(response);
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductResponse> getProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        ProductInfo info = productFacade.getProduct(productId);
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(info));
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductResponse> updateProduct(
        @PathVariable(value = "productId") Long productId,
        @RequestBody AdminProductV1Dto.UpdateRequest request
    ) {
        ProductInfo info = productFacade.updateProduct(productId, request.name(), request.price());
        return ApiResponse.success(ProductV1Dto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        productFacade.deleteProduct(productId);
    }

    @PutMapping("/{productId}/stock")
    public ApiResponse<AdminProductV1Dto.StockResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody AdminProductV1Dto.ChangeStockRequest request
    ) {
        int quantity = productFacade.changeStock(productId, request.quantity());
        return ApiResponse.success(new AdminProductV1Dto.StockResponse(productId, quantity));
    }
}
