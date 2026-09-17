package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "주문 고객 API")
public interface OrderV1ApiSpec {

    /** EP-10 POST /api/v1/orders — FR-ORDER-01 */
    @Operation(summary = "주문 생성", description = "품목 목록으로 DRAFT 주문을 만든다. 재고·잔액은 바뀌지 않는다.")
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(Long requesterId, OrderV1Dto.CreateOrderRequest request);

    /** EP-11 POST /api/v1/orders/{orderId}/confirm — FR-ORDER-02 */
    @Operation(summary = "주문 확정", description = "재고와 포인트를 차감하고 주문을 CONFIRMED 로 바꾼다. 전부 아니면 전무.")
    ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(Long requesterId, Long orderId);

    /** EP-12 GET /api/v1/orders — FR-ORDER-03 */
    @Operation(summary = "내 주문 목록", description = "요청자의 주문 전부를 최신순 페이지로 반환한다.")
    ApiResponse<PageResponse<OrderV1Dto.OrderResponse>> listMyOrders(Long requesterId, Integer page, Integer size);

    /** EP-13 GET /api/v1/orders/{orderId} — FR-ORDER-04 */
    @Operation(summary = "내 주문 상세")
    ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(Long requesterId, Long orderId);
}
