package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order Admin V1 API", description = "Loopers 관리자 주문 API 입니다.")
public interface OrderAdminV1ApiSpec {

    @Operation(
        summary = "전체 주문 목록 조회",
        description = "소유자와 무관하게 전체 주문 목록을 조회합니다."
    )
    ApiResponse<OrderV1Dto.OrderListResponse> getOrders();

    @Operation(
        summary = "주문 상세 조회",
        description = "소유자와 무관하게 주문 상세를 조회합니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> getOrder(
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );
}
