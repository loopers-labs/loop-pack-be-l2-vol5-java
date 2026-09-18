package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private final OrderFacade orderFacade;

    @PostMapping
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @LoginUserId Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        List<OrderItemCommand> items = request.items().stream()
            .map(item -> new OrderItemCommand(item.productId(), item.quantity()))
            .toList();
        var info = orderFacade.createOrder(userId, items);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @PathVariable Long orderId,
        @LoginUserId Long userId
    ) {
        var info = orderFacade.confirmOrder(orderId, userId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> getOrders(
        @LoginUserId Long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var orders = orderFacade.getOrders(userId, PageRequest.of(page, size));
        return ApiResponse.success(orders.map(OrderV1Dto.OrderResponse::from).getContent());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getOrder(
        @PathVariable Long orderId,
        @LoginUserId Long userId
    ) {
        var info = orderFacade.getOrder(orderId, userId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }
}
