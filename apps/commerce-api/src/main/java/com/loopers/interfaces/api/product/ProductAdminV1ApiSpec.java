package com.loopers.interfaces.api.product;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product Admin V1 API", description = "Loopers 관리자 상품 API 입니다.")
public interface ProductAdminV1ApiSpec {

    @Operation(summary = "상품 목록 조회", description = "삭제되지 않은 전체 상품 목록을 조회합니다.")
    ApiResponse<ProductAdminV1Dto.ProductListResponse> getProducts();

    @Operation(summary = "상품 상세 조회", description = "ID로 상품을 조회합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> getProduct(
        @Schema(name = "상품 ID") Long productId
    );

    @Operation(summary = "상품 생성", description = "새 상품을 등록합니다. 브랜드는 존재하며 삭제되지 않아야 합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> createProduct(
        @Schema(name = "상품 생성 요청", description = "이름·가격·브랜드ID·초기재고")
        ProductAdminV1Dto.CreateRequest request
    );

    @Operation(summary = "상품 수정", description = "상품의 이름·가격을 수정합니다. 브랜드는 변경되지 않습니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> updateProduct(
        @Schema(name = "상품 ID") Long productId,
        @Schema(name = "상품 수정 요청", description = "이름·가격")
        ProductAdminV1Dto.UpdateRequest request
    );

    @Operation(summary = "상품 삭제", description = "상품을 삭제(soft delete)합니다.")
    ApiResponse<Object> deleteProduct(
        @Schema(name = "상품 ID") Long productId
    );

    @Operation(summary = "상품 재고 변경", description = "상품의 재고를 절대값으로 설정합니다.")
    ApiResponse<ProductAdminV1Dto.ProductResponse> changeStock(
        @Schema(name = "상품 ID") Long productId,
        @Schema(name = "재고 변경 요청", description = "최종 수량(0 이상)")
        ProductAdminV1Dto.StockRequest request
    );
}
