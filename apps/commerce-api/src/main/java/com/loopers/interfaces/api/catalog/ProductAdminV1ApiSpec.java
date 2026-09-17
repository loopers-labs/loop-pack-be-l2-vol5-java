package com.loopers.interfaces.api.catalog;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product Admin V1 API", description = "상품 관리자 API")
public interface ProductAdminV1ApiSpec {

    /** EP-19 GET /api-admin/v1/products — FR-ADMIN-PRODUCT-01 */
    @Operation(summary = "상품 목록", description = "삭제 포함 전체 상품을 최신순 페이지로 반환한다.")
    ApiResponse<PageResponse<ProductAdminV1Dto.ProductAdminResponse>> listProducts(Long requesterId, Integer page, Integer size);

    /** EP-20 POST /api-admin/v1/products — FR-ADMIN-PRODUCT-02 */
    @Operation(summary = "상품 생성")
    ApiResponse<ProductAdminV1Dto.ProductAdminResponse> createProduct(Long requesterId, ProductAdminV1Dto.CreateRequest request);

    /** EP-21 GET /api-admin/v1/products/{productId} — FR-ADMIN-PRODUCT-03 */
    @Operation(summary = "상품 상세", description = "삭제 여부 무관하게 반환한다.")
    ApiResponse<ProductAdminV1Dto.ProductAdminResponse> getProduct(Long requesterId, Long productId);

    /** EP-22 PUT /api-admin/v1/products/{productId} — FR-ADMIN-PRODUCT-04 */
    @Operation(summary = "상품 수정", description = "이름·가격만 수정한다.")
    ApiResponse<ProductAdminV1Dto.ProductAdminResponse> updateProduct(
        Long requesterId, Long productId, ProductAdminV1Dto.UpdateRequest request);

    /** EP-23 DELETE /api-admin/v1/products/{productId} — FR-ADMIN-PRODUCT-05 */
    @Operation(summary = "상품 삭제")
    ApiResponse<Object> deleteProduct(Long requesterId, Long productId);

    /** EP-24 PUT /api-admin/v1/products/{productId}/stock — FR-ADMIN-PRODUCT-06 */
    @Operation(summary = "상품 재고 변경", description = "재고를 입력한 최종 수량으로 설정한다.")
    ApiResponse<ProductAdminV1Dto.StockResponse> updateStock(Long requesterId, Long productId, ProductAdminV1Dto.StockRequest request);
}
