package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Order V1 API", description = "고객 주문 API")
public interface OrderV1ApiSpec {

    ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> create(
        Long userId, OrderV1Dto.OrderCreateRequest request);

    ApiResponse<OrderV1Dto.OrderResponse> confirm(Long userId, Long orderId);

    ApiResponse<PageResponse<OrderV1Dto.OrderResponse>> getOrders(
        Long userId, Integer page, Integer size, String sort);

    ApiResponse<OrderV1Dto.OrderResponse> getOrder(Long userId, Long orderId);
}
