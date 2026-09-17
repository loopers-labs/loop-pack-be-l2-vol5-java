package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Admin Product V1 API", description = "관리자 상품·재고 API")
public interface ProductAdminV1ApiSpec {

    ApiResponse<PageResponse<ProductAdminV1Dto.AdminProductResponse>> getProducts(
        Integer page, Integer size, String sort);

    ResponseEntity<ApiResponse<ProductAdminV1Dto.AdminProductResponse>> create(
        ProductAdminV1Dto.ProductCreateRequest request);

    ApiResponse<ProductAdminV1Dto.AdminProductResponse> getProduct(Long productId);

    ApiResponse<ProductAdminV1Dto.AdminProductResponse> update(
        Long productId, ProductAdminV1Dto.ProductUpdateRequest request);

    ApiResponse<Object> delete(Long productId);

    ApiResponse<ProductAdminV1Dto.StockResponse> changeStock(
        Long productId, ProductAdminV1Dto.StockUpdateRequest request);
}
