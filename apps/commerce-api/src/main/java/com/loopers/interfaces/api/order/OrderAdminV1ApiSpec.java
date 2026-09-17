package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Order V1 API", description = "관리자 주문 조회 API")
public interface OrderAdminV1ApiSpec {

    ApiResponse<PageResponse<OrderAdminV1Dto.AdminOrderResponse>> getOrders(Integer page, Integer size, String sort);

    ApiResponse<OrderAdminV1Dto.AdminOrderResponse> getOrder(Long orderId);
}
