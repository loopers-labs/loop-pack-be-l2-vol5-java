package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller {

    private final OrderFacade orderFacade;

    @GetMapping
    public ApiResponse<OrderAdminV1Dto.OrdersResponse> getOrders(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(OrderAdminV1Dto.OrdersResponse.from(orderFacade.getOrdersForAdmin(page, size)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderAdminV1Dto.OrderResponse> getOrder(@PathVariable(value = "orderId") Long orderId) {
        return ApiResponse.success(OrderAdminV1Dto.OrderResponse.from(orderFacade.getOrderForAdmin(orderId)));
    }
}
