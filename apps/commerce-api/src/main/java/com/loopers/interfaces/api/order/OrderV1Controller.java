package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.support.CustomerId;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class OrderV1Controller implements OrderV1ApiSpec {

    private final OrderService orderService;
    private final OrderConfirmFacade orderConfirmFacade;

    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> create(
        @CustomerId Long userId,
        @RequestBody(required = false) OrderV1Dto.OrderCreateRequest request
    ) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new CoreException(ErrorType.INVALID_ORDER_ITEMS);
        }

        List<OrderItemCommand> commands = request.items().stream()
            .map(OrderV1Controller::toCommand)
            .toList();
        OrderModel created = orderService.create(userId, commands);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(OrderV1Dto.OrderResponse.from(created)));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> confirm(
        @CustomerId Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderInfo confirmed = orderConfirmFacade.confirm(userId, orderId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(confirmed));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderV1Dto.OrderResponse>> getOrders(
        @CustomerId Long userId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        PageResult<OrderModel> orders = orderService.getOrders(
            userId, PageCommand.of(page, size), ListSort.from(sort));
        return ApiResponse.success(PageResponse.of(orders, OrderV1Dto.OrderResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> getOrder(
        @CustomerId Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderModel order = orderService.getOrder(userId, orderId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(order));
    }

    private static OrderItemCommand toCommand(OrderV1Dto.OrderItemRequest item) {
        if (item == null || item.productId() == null || item.quantity() == null) {
            throw new CoreException(ErrorType.INVALID_ORDER_ITEMS);
        }
        return new OrderItemCommand(item.productId(), item.quantity());
    }
}
