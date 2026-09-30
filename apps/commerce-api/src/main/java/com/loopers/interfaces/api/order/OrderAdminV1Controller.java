package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderAdminFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller implements OrderAdminV1ApiSpec {

    private final OrderAdminFacade orderAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<OrderV1Dto.OrderListResponse> getOrders() {
        return ApiResponse.success(OrderV1Dto.OrderListResponse.from(orderAdminFacade.getOrders()));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> getOrder(
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderAdminFacade.getOrder(orderId)));
    }
}
