package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.order.OrderV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller {
    private final OrderFacade orderFacade;

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> getOrders() {
        return ApiResponse.success(orderFacade.getAllOrders().stream().map(OrderV1Dto.OrderResponse::from).toList());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.getOrder(orderId)));
    }
}
