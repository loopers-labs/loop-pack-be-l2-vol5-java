package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order API", description = "고객용 주문 API 입니다.")
public interface OrderApiSpec {

    @Operation(
        summary = "주문 생성",
        description = "여러 품목을 현재 상품 가격으로 DRAFT 주문으로 저장합니다. 재고·포인트는 차감하지 않고, 같은 상품 품목은 수량을 합칩니다."
    )
    ApiResponse<OrderDto.OrderResponse> create(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        OrderDto.CreateRequest request
    );

    @Operation(
        summary = "주문 확정",
        description = "본인의 DRAFT 주문을 확정 시점 가격으로 결제합니다. 재고·포인트를 차감하고 CONFIRMED로 바꿉니다."
    )
    ApiResponse<OrderDto.OrderResponse> confirm(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        @Schema(name = "주문 ID", description = "확정할 주문의 ID")
        Long orderId
    );

    @Operation(
        summary = "내 주문 목록 조회",
        description = "자신의 주문을 최신순으로 조회합니다."
    )
    ApiResponse<PageResponse<OrderDto.OrderResponse>> getOrders(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );

    @Operation(
        summary = "내 주문 상세 조회",
        description = "자신의 주문을 조회합니다. 품목의 상품이 삭제되었어도 저장된 값과 상품명을 보여줍니다."
    )
    ApiResponse<OrderDto.OrderResponse> getOrder(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );
}
