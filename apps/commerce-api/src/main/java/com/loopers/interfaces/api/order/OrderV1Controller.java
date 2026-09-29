package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final OrderFacade orderFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 하나 이상이어야 합니다.");
        }

        List<OrderCommand.Item> items = request.items().stream()
            .map(item -> new OrderCommand.Item(item.productId(), item.quantity()))
            .toList();

        OrderInfo info = orderFacade.createOrder(userId, items);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderInfo info = orderFacade.confirmOrder(userId, orderId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> getOrders(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId
    ) {
        List<OrderV1Dto.OrderResponse> response = orderFacade.getOrders(userId).stream()
            .map(OrderV1Dto.OrderResponse::from)
            .toList();

        return ApiResponse.success(response);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderInfo info = orderFacade.getOrder(userId, orderId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }
}
