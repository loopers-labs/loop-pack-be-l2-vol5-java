package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller {

    private final OrderFacade orderFacade;

    @GetMapping
    public ApiResponse<List<OrderAdminV1Dto.OrderResponse>> getOrders(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var orders = orderFacade.getOrdersForAdmin(PageRequest.of(page, size));
        return ApiResponse.success(orders.map(OrderAdminV1Dto.OrderResponse::from).getContent());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderAdminV1Dto.OrderResponse> getOrder(@PathVariable Long orderId) {
        var info = orderFacade.getOrderForAdmin(orderId);
        return ApiResponse.success(OrderAdminV1Dto.OrderResponse.from(info));
    }
}
