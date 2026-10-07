package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class ProductAdminV1Controller implements ProductAdminV1ApiSpec {

    private final ProductFacade productFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<ProductAdminV1Dto.AdminProductResponse>> getProducts(
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        PageResult<ProductQueryResult> result =
            productFacade.getAllProducts(PageCommand.of(page, size), ListSort.from(sort));
        return ApiResponse.success(PageResponse.of(result, ProductAdminV1Dto.AdminProductResponse::from));
    }

    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<ProductAdminV1Dto.AdminProductResponse>> create(
        @RequestBody(required = false) ProductAdminV1Dto.ProductCreateRequest request
    ) {
        ProductAdminV1Dto.ProductCreateRequest body = request != null
            ? request
            : new ProductAdminV1Dto.ProductCreateRequest(null, null, null);
        if (body.brandId() == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }

        ProductQueryResult created = productFacade.create(body.brandId(), body.name(), body.price());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(created)));
    }

    @GetMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> getProduct(
        @PathVariable(value = "productId") Long productId
    ) {
        ProductQueryResult product = productFacade.getProduct(productId);
        return ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(product));
    }

    @PutMapping("/{productId}")
    @Override
    public ApiResponse<ProductAdminV1Dto.AdminProductResponse> update(
        @PathVariable(value = "productId") Long productId,
        @RequestBody(required = false) ProductAdminV1Dto.ProductUpdateRequest request
    ) {
        ProductAdminV1Dto.ProductUpdateRequest body = request != null
            ? request
            : new ProductAdminV1Dto.ProductUpdateRequest(null, null);
        ProductQueryResult updated = productFacade.update(productId, body.name(), body.price());
        return ApiResponse.success(ProductAdminV1Dto.AdminProductResponse.from(updated));
    }

    @DeleteMapping("/{productId}")
    @Override
    public ApiResponse<Object> delete(@PathVariable(value = "productId") Long productId) {
        productFacade.delete(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    @Override
    public ApiResponse<ProductAdminV1Dto.StockResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody(required = false) ProductAdminV1Dto.StockUpdateRequest request
    ) {
        ProductModel changed = productFacade.changeStock(productId, request != null ? request.quantity() : null);
        return ApiResponse.success(ProductAdminV1Dto.StockResponse.from(changed));
    }
}
