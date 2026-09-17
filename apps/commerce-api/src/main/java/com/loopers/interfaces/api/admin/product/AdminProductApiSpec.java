package com.loopers.interfaces.api.admin.product;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Product API", description = "관리자용 상품 API 입니다. ROLE_ADMIN 권한이 필요합니다.")
public interface AdminProductApiSpec {

    @Operation(summary = "상품 목록 조회", description = "삭제되지 않은 상품을 최신순으로 조회합니다.")
    ApiResponse<PageResponse<AdminProductDto.ProductResponse>> getProducts(
        @Schema(name = "브랜드 ID", description = "브랜드 필터 (선택)")
        Long brandId,
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );

    @Operation(summary = "상품 상세 조회", description = "삭제되지 않은 상품을 재고·등록·수정 시각과 함께 조회합니다.")
    ApiResponse<AdminProductDto.ProductResponse> getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        Long productId
    );

    @Operation(summary = "상품 등록", description = "존재하며 삭제되지 않은 브랜드에 상품을 등록합니다.")
    ApiResponse<AdminProductDto.ProductResponse> register(AdminProductDto.CreateRequest request);

    @Operation(summary = "상품 수정", description = "이름·가격만 수정합니다. 브랜드와 재고는 바뀌지 않습니다.")
    ApiResponse<AdminProductDto.ProductResponse> update(
        @Schema(name = "상품 ID", description = "수정할 상품의 ID")
        Long productId,
        AdminProductDto.UpdateRequest request
    );

    @Operation(summary = "상품 재고 변경", description = "0 이상인 최종 수량으로 재고를 설정합니다.")
    ApiResponse<AdminProductDto.ProductResponse> changeStock(
        @Schema(name = "상품 ID", description = "재고를 변경할 상품의 ID")
        Long productId,
        AdminProductDto.StockRequest request
    );

    @Operation(summary = "상품 삭제", description = "상품을 논리 삭제합니다. 과거 주문 내역에서는 계속 조회됩니다.")
    ApiResponse<Object> delete(
        @Schema(name = "상품 ID", description = "삭제할 상품의 ID")
        Long productId
    );
}
