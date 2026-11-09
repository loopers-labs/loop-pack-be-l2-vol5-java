package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminV1Controller {

    private final ProductFacade productFacade;

    @GetMapping
    public ApiResponse<List<ProductAdminV1Dto.ProductResponse>> getProducts(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var products = productFacade.getProductsForAdmin(PageRequest.of(page, size));
        return ApiResponse.success(products.map(ProductAdminV1Dto.ProductResponse::from).getContent());
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(@PathVariable Long productId) {
        var info = productFacade.getProductForAdmin(productId);
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @PostMapping
    public ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(@RequestBody ProductAdminV1Dto.ProductCreateRequest request) {
        var info = productFacade.createProduct(request.brandId(), request.name(), request.price(), request.stock());
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.ProductUpdateRequest request
    ) {
        var info = productFacade.updateProduct(productId, request.name(), request.price());
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @PatchMapping("/{productId}/stock")
    public ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        var info = productFacade.changeStock(productId, request.stock());
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Object> deleteProduct(@PathVariable Long productId) {
        productFacade.deleteProduct(productId);
        return ApiResponse.success();
    }
}
