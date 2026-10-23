package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentReconcileFacade;
import com.loopers.application.payment.PaymentReflection;
import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.payment.PaymentStatusInfo;
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
import java.util.List;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {
    private final OrderFacade orderFacade;
    private final PaymentRequestFacade paymentRequestFacade;
    private final PaymentReconcileFacade paymentReconcileFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderV1Dto.OrderResponse> create(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @RequestBody OrderV1Dto.CreateRequest request
    ) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.create(userId, request.toOrderRequestItems())));
    }

    // 결제 대기(미종결)이면 202, 종결된 결제 상태이면 200으로 응답한다.
    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<ApiResponse<OrderV1Dto.PaymentStatusResponse>> confirm(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long orderId,
        @RequestBody OrderV1Dto.ConfirmRequest request
    ) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
        PaymentStatusInfo status = paymentRequestFacade.requestPayment(
            userId, orderId, request.paymentAttemptKey(), request.cardType(), request.cardNo()
        );
        return paymentStatus(status);
    }

    // 본인 주문의 기존 결제 결과를 PG에 다시 조회해 반영한다. 결제 대기이면 202, 종결이면 200이다.
    @PostMapping("/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<OrderV1Dto.PaymentStatusResponse>> reconcile(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long orderId
    ) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
        PaymentReflection reflection = paymentReconcileFacade.reconcile(userId, orderId);
        return ResponseEntity.status(reflection.payment().isUnterminated() ? HttpStatus.ACCEPTED : HttpStatus.OK)
            .body(ApiResponse.success(OrderV1Dto.PaymentStatusResponse.from(reflection)));
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

    private ResponseEntity<ApiResponse<OrderV1Dto.PaymentStatusResponse>> paymentStatus(PaymentStatusInfo status) {
        return ResponseEntity.status(status.isUnterminated() ? HttpStatus.ACCEPTED : HttpStatus.OK)
            .body(ApiResponse.success(OrderV1Dto.PaymentStatusResponse.from(status)));
    }
}
