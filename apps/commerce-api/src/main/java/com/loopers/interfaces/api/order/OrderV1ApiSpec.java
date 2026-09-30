package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "Loopers 주문 API 입니다.")
public interface OrderV1ApiSpec {

    @Operation(
        summary = "주문 생성",
        description = "DRAFT 상태의 주문을 생성합니다. 재고·포인트는 차감하지 않습니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        OrderV1Dto.CreateRequest request
    );

    @Operation(
        summary = "주문 확정",
        description = "DRAFT 주문을 확정합니다. 재고·포인트를 차감하고 결제 결과를 저장합니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "주문 ID", description = "확정할 주문의 ID")
        Long orderId
    );

    @Operation(
        summary = "내 주문 목록 조회",
        description = "요청자의 주문 목록을 조회합니다."
    )
    ApiResponse<OrderV1Dto.OrderListResponse> getMyOrders(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId
    );

    @Operation(
        summary = "내 주문 상세 조회",
        description = "요청자의 주문 상세를 조회합니다. 요청자가 소유자가 아니면 404를 반환합니다(존재 비노출)."
    )
    ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );
}
