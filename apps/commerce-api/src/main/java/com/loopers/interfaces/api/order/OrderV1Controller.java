package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.payment.Payment;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderV1Controller {

    private static final String RETRY_AFTER_SECONDS = "1";

    private final OrderFacade orderFacade;
    private final PaymentFacade paymentFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderV1Dto.OrderResponse> place(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody OrderV1Dto.PlaceRequest request
    ) {
        return ApiResponse.success(
            OrderV1Dto.OrderResponse.from(orderFacade.place(request.toCommand(userId), Instant.now())));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ApiResponse<OrderV1Dto.ConfirmResponse>> confirm(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long orderId,
        @RequestBody OrderV1Dto.ConfirmRequest request
    ) {
        Payment payment = paymentFacade.confirm(request.toCommand(userId, orderId), Instant.now());
        ApiResponse<OrderV1Dto.ConfirmResponse> body = ApiResponse.success(
            OrderV1Dto.ConfirmResponse.of(orderFacade.get(userId, orderId), payment));
        if (payment.isSettled()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.accepted().header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(body);
    }

    @GetMapping
    public ApiResponse<List<OrderV1Dto.OrderResponse>> findMine(@RequestHeader("X-USER-ID") Long userId) {
        return ApiResponse.success(
            orderFacade.findMine(userId).stream().map(OrderV1Dto.OrderResponse::from).toList());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderResponse> get(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.get(userId, orderId)));
    }
}
