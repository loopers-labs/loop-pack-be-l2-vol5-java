package com.loopers.interfaces.api.order;

import com.loopers.application.order.GetOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetOrderController {
    private final GetOrderFacade facade;
    @GetMapping("/api/v1/orders/{orderId}")
    public ApiResponse<OrderDto.Customer> get(@RequestHeader(value = "X-USER-ID", required = false) Long userId,
                                             @PathVariable long orderId) {
        return ApiResponse.success(OrderDto.Customer.from(facade.get(userId,orderId)));
    }
    @GetMapping("/api/v1/orders")
    public ApiResponse<OrderDto.OrderList> list(@RequestHeader(value = "X-USER-ID", required = false) Long userId) {
        return ApiResponse.success(new OrderDto.OrderList(facade.list(userId).stream().map(OrderDto.Customer::from).toList()));
    }
}
