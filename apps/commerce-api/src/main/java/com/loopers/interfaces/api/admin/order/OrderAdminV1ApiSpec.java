package com.loopers.interfaces.api.admin.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order Admin V1 API", description = "관리자용 주문 조회 API")
public interface OrderAdminV1ApiSpec {

    @Operation(summary = "주문 목록 조회", description = "모든 구매자의 주문을 최신순으로 조회합니다. userId로 구매자를 거를 수 있습니다.")
    ApiResponse<PageResponse<OrderAdminV1Dto.OrderSummaryResponse>> getOrders(Long userId, Integer page, Integer size);

    @Operation(summary = "주문 상세 조회", description = "품목·상태·금액·결제 결과와 구매자를 조회합니다.")
    ApiResponse<OrderAdminV1Dto.OrderResponse> getOrder(Long orderId);
}
