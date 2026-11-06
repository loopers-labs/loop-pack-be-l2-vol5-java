package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentOutcome;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
public class OrderV1Controller {

    private final OrderFacade orderFacade;
    private final PaymentFacade paymentFacade;

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

    /** PG 결제를 시작한다. 결과를 기다리거나 모르면 202, 결제가 끝났으면 200이다. */
    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(
        @PathVariable Long orderId,
        @LoginUserId Long userId,
        @RequestBody OrderV1Dto.ConfirmRequest request
    ) {
        PaymentOutcome outcome = paymentFacade.confirm(
            orderId, userId, request.paymentAttemptKey(), request.cardType(), request.cardNo()
        );
        return respond(outcome);
    }

    /** 기존 외부 거래를 조회해 결과를 적용한다. 새 결제 요청은 만들지 않는다. */
    @PostMapping("/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reconcilePayment(
        @PathVariable Long orderId,
        @LoginUserId Long userId
    ) {
        return respond(paymentFacade.reconcile(orderId, userId));
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

    private static ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> respond(PaymentOutcome outcome) {
        HttpStatus status = outcome.settled() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(OrderV1Dto.OrderResponse.from(outcome.order())));
    }
}
