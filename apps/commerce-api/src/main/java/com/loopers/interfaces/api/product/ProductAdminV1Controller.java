package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductAdminFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminV1Controller implements ProductAdminV1ApiSpec {

    private final ProductFacade productFacade;
    private final ProductAdminFacade productAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductListResponse> getProducts() {
        return ApiResponse.success(ProductAdminV1Dto.ProductListResponse.from(productFacade.getProducts()));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        ProductInfo info = productFacade.getProduct(productId);
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(
        @RequestBody ProductAdminV1Dto.CreateRequest request
    ) {
        if (request.initialStock() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "초기 재고는 비어있을 수 없습니다.");
        }
        ProductInfo info = productAdminFacade.createProduct(
            request.name(), request.price(), request.brandId(), request.initialStock()
        );
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(
        @PathVariable(value = "productId") Long productId,
        @RequestBody ProductAdminV1Dto.UpdateRequest request
    ) {
        ProductInfo info = productAdminFacade.updateProduct(productId, request.name(), request.price());
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> deleteProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        productAdminFacade.deleteProduct(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        if (request.quantity() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 수량은 비어있을 수 없습니다.");
        }
        ProductInfo info = productAdminFacade.changeStock(productId, request.quantity());
        return ApiResponse.success(ProductAdminV1Dto.ProductResponse.from(info));
    }
}
