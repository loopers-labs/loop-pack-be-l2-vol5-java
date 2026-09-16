package com.loopers.interfaces.api.admin.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product Admin V1 API", description = "관리자용 상품 API")
public interface ProductAdminV1ApiSpec {

    @Operation(summary = "상품 목록 조회", description = "삭제되지 않은 상품을 최신 등록순으로 조회합니다.")
    ApiResponse<PageResponse<ProductAdminV1Dto.ProductResponse>> getProducts(Long brandId, Integer page, Integer size);

    @Operation(summary = "상품 등록", description = "존재하며 삭제되지 않은 브랜드를 참조해야 합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(ProductAdminV1Dto.CreateRequest request);

    @Operation(summary = "상품 상세 조회")
    ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(Long productId);

    @Operation(summary = "상품 수정", description = "이름·가격만 바꾸고 브랜드는 유지합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(Long productId, ProductAdminV1Dto.UpdateRequest request);

    @Operation(summary = "상품 삭제")
    ApiResponse<Object> deleteProduct(Long productId);

    @Operation(summary = "상품 재고 변경", description = "0 이상의 최종 수량으로 설정합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(Long productId, ProductAdminV1Dto.StockRequest request);
}
