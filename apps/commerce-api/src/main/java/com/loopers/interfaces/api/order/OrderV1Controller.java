package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {
    private final OrderFacade orderFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderV1Dto.OrderResponse> create(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.create(userId, request.items())));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderV1Dto.OrderResponse> confirm(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long orderId
    ) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.confirm(userId, orderId)));
    }

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> getMyOrders(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId
    ) {
        if (userId == null) throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        return ApiResponse.success(orderFacade.getMyOrders(userId).stream().map(OrderV1Dto.OrderResponse::from).toList());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long orderId
    ) {
        if (userId == null) throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.getMyOrder(userId, orderId)));
    }
}
