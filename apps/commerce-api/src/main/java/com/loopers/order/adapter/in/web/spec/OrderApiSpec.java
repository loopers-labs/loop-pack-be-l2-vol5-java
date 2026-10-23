package com.loopers.order.adapter.in.web.spec;

import com.loopers.order.adapter.in.web.dto.OrderDto;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "고객용 주문 API (X-USER-ID 필요)")
public interface OrderApiSpec {

    @Operation(summary = "주문 생성", description = "여러 품목·수량을 DRAFT로 저장합니다. 같은 상품은 합산하고, 생성 시 재고·포인트는 차감하지 않습니다.")
    ApiResponse<OrderDto.OrderResponse> createOrder(@Parameter(hidden = true) LoginUser loginUser, OrderDto.CreateRequest request);

    @Operation(summary = "내 주문 목록", description = "최신 주문순")
    ApiResponse<PageResponse<OrderDto.OrderSummaryResponse>> getMyOrders(@Parameter(hidden = true) LoginUser loginUser, Integer page, Integer size);

    @Operation(summary = "내 주문 상세", description = "없거나 남의 주문이면 404")
    ApiResponse<OrderDto.OrderResponse> getMyOrder(@Parameter(hidden = true) LoginUser loginUser, Long orderId);
}
