package com.loopers.product.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.RequestFields;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.Product;
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
public class ProductAdminV1Controller {

    private final ProductUseCase productUseCase;

    @GetMapping
    public ApiResponse<ListResponse<ProductV1Dto.AdminProductResponse>> getProducts(
        @RequestParam(required = false) Long brandId,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            productUseCase.findAdminProductPage(brandId, query.page(), query.size()),
            ProductV1Dto.AdminProductResponse::from
        ));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductV1Dto.AdminProductResponse> createProduct(
        @RequestBody ProductV1Dto.CreateProductRequest request
    ) {
        Product product = productUseCase.create(
            RequestFields.required(request.brandId()),
            RequestFields.required(request.name()),
            RequestFields.required(request.price())
        );
        return adminProduct(product.getId());
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.AdminProductResponse> getProduct(@PathVariable Long productId) {
        return adminProduct(productId);
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductV1Dto.AdminProductResponse> updateProduct(
        @PathVariable Long productId,
        @RequestBody ProductV1Dto.UpdateProductRequest request
    ) {
        productUseCase.update(
            productId,
            RequestFields.required(request.name()),
            RequestFields.required(request.price()),
            request.brandId()
        );
        return adminProduct(productId);
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Object> deleteProduct(@PathVariable Long productId) {
        productUseCase.delete(productId);
        return ApiResponse.success();
    }

    @PutMapping("/{productId}/stock")
    public ApiResponse<ProductV1Dto.AdminProductResponse> changeStock(
        @PathVariable Long productId,
        @RequestBody ProductV1Dto.StockRequest request
    ) {
        productUseCase.changeStock(productId, RequestFields.required(request.quantity()));
        return adminProduct(productId);
    }

    private ApiResponse<ProductV1Dto.AdminProductResponse> adminProduct(Long productId) {
        return ApiResponse.success(
            ProductV1Dto.AdminProductResponse.from(productUseCase.findAdminProduct(productId))
        );
    }
}
