package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.support.paging.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "주문 고객 API")
public interface OrderV1ApiSpec {

    /** EP-10 POST /api/v1/orders — FR-ORDER-01 */
    @Operation(summary = "주문 생성", description = "품목 목록으로 DRAFT 주문을 만든다. 재고·잔액은 바뀌지 않는다.")
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(Long requesterId, OrderV1Dto.CreateOrderRequest request);

    /** EP-11 POST /api/v1/orders/{orderId}/confirm — FR-ORDER-02 */
    @Operation(summary = "주문 확정", description = "재고를 예약하고 PG 카드 결제를 요청한다. 결과에 따라 CONFIRMED·FAILED, 결과를 모르면 AWAITING_PAYMENT.")
    ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(Long requesterId, Long orderId, OrderV1Dto.ConfirmOrderRequest request);

    /** POST /api/v1/orders/{orderId}/payment/resolve-awaiting-payment — FR-ORDER-02 결제 대기 해소 */
    @Operation(summary = "결제 대기 해소", description = "본인 주문이 AWAITING_PAYMENT 면 PG 에 기존 거래를 조회해 결과를 반영한다. 새 결제 요청을 만들지 않는다. AWAITING_PAYMENT 가 아니면 현재 상태를 반환한다.")
    ApiResponse<OrderV1Dto.OrderResponse> resolveAwaitingPayment(Long requesterId, Long orderId);

    /** EP-12 GET /api/v1/orders — FR-ORDER-03 */
    @Operation(summary = "내 주문 목록", description = "요청자의 주문 전부를 최신순 페이지로 반환한다.")
    ApiResponse<PageResponse<OrderV1Dto.OrderResponse>> listMyOrders(Long requesterId, PageQuery pageQuery);

    /** EP-13 GET /api/v1/orders/{orderId} — FR-ORDER-04 */
    @Operation(summary = "내 주문 상세", description = "본인 주문의 품목·결제수단·결제 상태(AWAITING_PAYMENT·CONFIRMED·FAILED)를 반환한다. 새 결제 요청을 만들지 않는다.")
    ApiResponse<OrderV1Dto.MyOrderResponse> getMyOrder(Long requesterId, Long orderId);
}
