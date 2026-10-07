package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final OrderFacade orderFacade;

    @PostMapping
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(
            orderFacade.createOrder(requireUserId(userId), request.toCommands())
        ));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(
            orderFacade.confirmOrder(requireUserId(userId), orderId)
        ));
    }

    @GetMapping
    public ApiResponse<OrderV1Dto.OrdersResponse> getMyOrders(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId
    ) {
        return ApiResponse.success(OrderV1Dto.OrdersResponse.from(orderFacade.getMyOrders(requireUserId(userId))));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(
            orderFacade.getMyOrder(requireUserId(userId), orderId)
        ));
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, USER_ID_HEADER + " 헤더가 필요합니다.");
        }
        return userId;
    }
}
