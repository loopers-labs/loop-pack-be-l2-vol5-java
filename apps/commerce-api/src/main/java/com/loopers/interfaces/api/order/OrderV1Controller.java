package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class OrderV1Controller implements OrderV1ApiSpec {

    private final OrderFacade orderFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        List<OrderV1Dto.CreateRequest.ItemRequest> requestedItems =
            request.items() == null ? List.of() : request.items();
        List<OrderItemRequest> items = requestedItems.stream()
            .map(OrderV1Controller::toItemRequest)
            .toList();
        OrderInfo info = orderFacade.createOrder(userId, items);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }

    private static OrderItemRequest toItemRequest(OrderV1Dto.CreateRequest.ItemRequest item) {
        if (item.productId() == null || item.quantity() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "productId와 quantity는 비어있을 수 없습니다.");
        }
        return new OrderItemRequest(item.productId(), item.quantity());
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "orderId") Long orderId,
        @RequestBody OrderV1Dto.ConfirmRequest request
    ) {
        return paymentResponse(orderFacade.confirmOrder(orderId, userId, request.toCommand()));
    }

    @PostMapping("/{orderId}/payment/reconcile")
    @Override
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reconcilePayment(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        return paymentResponse(orderFacade.reconcilePayment(orderId, userId));
    }

    // 결제가 아직 끝나지 않았어도 요청은 정상으로 받아들였다 — 202에도 meta.result는 SUCCESS다(docs/week5/design.md 6번).
    private static ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> paymentResponse(OrderInfo info) {
        HttpStatus status = OrderV1Dto.OrderResponse.paymentInProgress(info) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(OrderV1Dto.OrderResponse.from(info)));
    }

    @GetMapping
    @Override
    public ApiResponse<OrderV1Dto.OrderListResponse> getMyOrders(
        @RequestHeader("X-USER-ID") Long userId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderListResponse.from(orderFacade.getMyOrders(userId)));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderInfo info = orderFacade.getMyOrder(orderId, userId);
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(info));
    }
}
