package com.loopers.payment.adapter.in.web;

import com.loopers.payment.adapter.in.web.dto.PaymentDto;
import com.loopers.payment.adapter.in.web.spec.PaymentApiSpec;
import com.loopers.payment.application.PaymentQueryService;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 결제 API (W5 plan.md 8). 결과가 아직이면 202, 정해졌으면 200 — 클라이언트는 202를 받으면 결제 상태를 다시 조회한다.
 * callback은 PG가 부르므로 X-USER-ID(LoginUser)를 받지 않는다.
 */
@RequiredArgsConstructor
@RestController
public class PaymentController implements PaymentApiSpec {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentRequestUseCase paymentRequestUseCase;
    private final PaymentReconcileUseCase paymentReconcileUseCase;
    private final PaymentQueryService paymentQueryService;

    /**
     * 헤더가 없으면 Spring의 누락 예외(500으로 떨어짐) 대신 유스케이스가 400으로 거절하도록 선택 헤더로 받는다.
     * 접수 뒤 상태를 다시 읽는다 — commit 직후 발송 풀 거절로 이미 실패로 닫혔을 수 있다 (ADR-W5-03).
     */
    @PostMapping("/api/v1/orders/{orderId}/confirm")
    @Override
    public ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> confirmOrder(
        LoginUser loginUser,
        @PathVariable Long orderId,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        @RequestBody PaymentDto.ConfirmRequest request
    ) {
        paymentRequestUseCase.request(request.toCommand(loginUser.id(), orderId, idempotencyKey));
        return respond(paymentQueryService.getMyPayment(loginUser.id(), orderId));
    }

    @GetMapping("/api/v1/orders/{orderId}/payment")
    @Override
    public ApiResponse<PaymentDto.PaymentResponse> getPayment(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(PaymentDto.PaymentResponse.from(paymentQueryService.getMyPayment(loginUser.id(), orderId)));
    }

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    @Override
    public ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> reconcilePayment(LoginUser loginUser, @PathVariable Long orderId) {
        return respond(paymentReconcileUseCase.reconcile(loginUser.id(), orderId));
    }

    @PostMapping("/api/v1/payments/callback")
    @Override
    public ApiResponse<Object> callback(@RequestBody PaymentDto.CallbackRequest request) {
        paymentReconcileUseCase.reconcileByCallback(request.orderId());
        return ApiResponse.success();
    }

    private static ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> respond(PaymentInfo info) {
        HttpStatus status = info.isFinal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(PaymentDto.PaymentResponse.from(info)));
    }
}
