package com.loopers.interfaces.api.product;

import com.loopers.domain.product.ProductAdminQuery;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.ProductService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api-admin/v1/products")
@RequiredArgsConstructor
public class ProductAdminV1Controller {

    private final ProductFacade productFacade;
    private final ProductService productService;
    private final ProductAdminQuery productAdminQuery;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> register(
        @RequestBody ProductAdminV1Dto.RegisterRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(
            productFacade.register(request.brandId(), request.name(), request.toPrice())));
    }

    @GetMapping
    public ApiResponse<ProductAdminV1Dto.AdminProductPageResponse> findPage(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = "LATEST") ProductAdminQuery.Sort sort,
        @RequestParam(defaultValue = "0") PageNumber page,
        @RequestParam(defaultValue = "20") PageSize size
    ) {
        return ApiResponse.success(ProductAdminV1Dto.AdminProductPageResponse.of(
            productAdminQuery.findPage(brandId, sort, page, size), page.value(), size.value()));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> get(@PathVariable Long productId) {
        return ApiResponse.success(
            ProductAdminV1Dto.AdminProductResponse.from(productService.get(productId)));
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> update(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(
            productFacade.update(productId, request.name(), request.toPrice())));
    }

    @PutMapping("/{productId}/stock")
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> adjustStock(
        @PathVariable Long productId,
        @RequestBody ProductAdminV1Dto.StockRequest request
    ) {
        return ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(
            productFacade.adjustStock(productId, request.toQuantity())));
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable Long productId) {
        productFacade.delete(productId);
        return ResponseEntity.noContent().build();
    }
}
