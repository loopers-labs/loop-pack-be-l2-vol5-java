package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller implements OrderV1ApiSpec {

    private final OrderFacade orderFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @RequesterId Long requesterId,
        @RequestBody OrderV1Dto.CreateOrderRequest request
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.createOrder(requesterId, request.toCommands())));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @RequesterId Long requesterId,
        @PathVariable("orderId") Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.confirmOrder(requesterId, orderId)));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderV1Dto.OrderResponse>> listMyOrders(
        @RequesterId Long requesterId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size
    ) {
        var result = orderFacade.listMyOrders(requesterId, PageQuery.of(page, size));
        return ApiResponse.success(PageResponse.from(result, OrderV1Dto.OrderResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @RequesterId Long requesterId,
        @PathVariable("orderId") Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.getMyOrder(requesterId, orderId)));
    }
}
