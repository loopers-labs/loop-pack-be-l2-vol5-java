package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderPaymentInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private final OrderFacade orderFacade;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> create(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            OrderV1Dto.OrderResponse.from(orderFacade.create(
                userId,
                request.items().stream()
                    .map(item -> new OrderFacade.OrderLine(item.productId(), item.quantity()))
                    .toList()
            ))
        ));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ApiResponse<OrderV1Dto.PaymentResponse>> confirm(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long orderId,
        @RequestBody OrderV1Dto.ConfirmRequest request
    ) {
        OrderFacade.PaymentCommand command = new OrderFacade.PaymentCommand(
            request.paymentAttemptKey(), request.cardType(), request.cardNo()
        );
        return paymentResponse(orderFacade.confirm(userId, orderId, command));
    }

    @PostMapping("/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<OrderV1Dto.PaymentResponse>> reconcile(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long orderId
    ) {
        return paymentResponse(orderFacade.reconcile(userId, orderId));
    }

    private static ResponseEntity<ApiResponse<OrderV1Dto.PaymentResponse>> paymentResponse(OrderPaymentInfo payment) {
        HttpStatus status = payment.isFinished() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(OrderV1Dto.PaymentResponse.from(payment)));
    }

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> getMyOrders(@RequestHeader("X-USER-ID") Long userId) {
        return ApiResponse.success(orderFacade.getMyOrders(userId).stream().map(OrderV1Dto.OrderResponse::from).toList());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.getMyOrder(userId, orderId)));
    }
}
