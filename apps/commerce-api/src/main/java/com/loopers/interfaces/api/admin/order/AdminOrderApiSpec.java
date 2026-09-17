package com.loopers.interfaces.api.admin.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Order API", description = "관리자용 주문 API 입니다. ROLE_ADMIN 권한이 필요합니다.")
public interface AdminOrderApiSpec {

    @Operation(summary = "주문 목록 조회", description = "구매자별 주문을 최신순으로 조회합니다.")
    ApiResponse<PageResponse<AdminOrderDto.OrderResponse>> getOrders(
        @Schema(name = "구매자 ID", description = "구매자 필터 (선택)")
        Long userId,
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );

    @Operation(summary = "주문 상세 조회", description = "주문의 품목·상태·금액·결제 결과를 구매자 ID와 함께 조회합니다.")
    ApiResponse<AdminOrderDto.OrderResponse> getOrder(
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );
}
