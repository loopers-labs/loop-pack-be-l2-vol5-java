package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController implements OrderApiSpec {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final OrderFacade orderFacade;

    @PostMapping
    @Override
    public ApiResponse<OrderDto.OrderResponse> create(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @RequestBody OrderDto.CreateRequest request
    ) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderFacade.create(userId, request.toCommands())));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderDto.OrderResponse> confirm(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderFacade.confirm(userId, orderId)));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderDto.OrderResponse>> getOrders(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(orderFacade.getOrders(userId, page, size), OrderDto.OrderResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderDto.OrderResponse> getOrder(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderFacade.getOrder(userId, orderId)));
    }
}
