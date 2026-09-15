package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.LoginUser;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "고객용 주문 API (X-USER-ID 필요)")
public interface OrderV1ApiSpec {

    @Operation(summary = "주문 생성", description = "여러 품목·수량을 DRAFT로 저장합니다. 같은 상품은 합산하고, 생성 시 재고·포인트는 차감하지 않습니다.")
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(@Parameter(hidden = true) LoginUser loginUser, OrderV1Dto.CreateRequest request);

    @Operation(summary = "내 주문 목록", description = "최신 주문순")
    ApiResponse<PageResponse<OrderV1Dto.OrderSummaryResponse>> getMyOrders(@Parameter(hidden = true) LoginUser loginUser, Integer page, Integer size);

    @Operation(summary = "내 주문 상세", description = "없거나 남의 주문이면 404")
    ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(@Parameter(hidden = true) LoginUser loginUser, Long orderId);
}
