package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 결과 맞추기 진입점 두 개. callback 은 PG 가, reconcile 은 사용자가 부른다. 둘 다 PaymentFacade 만 호출한다 (설계 6장).
 * 종결(SUCCESS·FAILED)이면 200, 진행 중이면 202 로 같은 모양의 본문을 돌려준다.
 */
@RequiredArgsConstructor
@RestController
public class PaymentV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final PaymentFacade paymentFacade;

    /**
     * 보낸 쪽 인증은 하지 않는다. 방어선은 PG 권위 조회다 (설계 7-5 결정 ⑲).
     */
    @PostMapping("/api/v1/payments/callback")
    public ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> callback(
        @RequestBody PaymentV1Dto.CallbackRequest request
    ) {
        OrderConfirmInfo info = paymentFacade.applyCallback(request.providerOrderId(), request.transactionKey());
        return ResponseEntity.status(statusOf(info))
            .body(ApiResponse.success(PaymentV1Dto.CallbackResponse.from(request.providerOrderId(), info)));
    }

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<PaymentV1Dto.ReconcileResponse>> reconcile(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderConfirmInfo info = paymentFacade.reconcile(userId, orderId);
        return ResponseEntity.status(statusOf(info))
            .body(ApiResponse.success(PaymentV1Dto.ReconcileResponse.from(info)));
    }

    private static HttpStatus statusOf(OrderConfirmInfo info) {
        return info.settled() ? HttpStatus.OK : HttpStatus.ACCEPTED;
    }
}
