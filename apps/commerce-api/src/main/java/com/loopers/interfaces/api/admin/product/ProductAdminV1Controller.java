package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
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
public class ProductAdminV1Controller {

    private final ProductFacade productFacade;

    @GetMapping
    public ApiResponse<ProductAdminV1Dto.ProductsResponse> getProducts(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(
            ProductAdminV1Dto.ProductsResponse.from(productFacade.getProductsForAdmin(page, size))
        );
    }

    @PostMapping
    public ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(
        @RequestBody ProductAdminV1Dto.CreateRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(
            productFacade.createProduct(request.brandId(), request.name(), request.price(), request.stock())
        ));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(
            ProductAdminV1Dto.ProductResponse.from(productFacade.getProductForAdmin(productId))
        );
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(
        @PathVariable(value = "productId") Long productId,
        @RequestBody ProductAdminV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(
            productFacade.updateProduct(productId, request.name(), request.price())
        ));
    }

    @PutMapping("/{productId}/stock")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(
            productFacade.changeStock(productId, request.quantity())
        ));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Object> deleteProduct(@PathVariable(value = "productId") Long productId) {
        productFacade.deleteProduct(productId);
        return ApiResponse.success();
    }
}
