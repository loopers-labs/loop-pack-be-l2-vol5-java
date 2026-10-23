package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentOutcome;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG가 부르는 엔드포인트다. 고객 식별 헤더(@LoginUserId)를 쓰지 않는다. PG로 보내는 {@code X-USER-ID: 135135}는 제공자 계정이고
 * 고객 인증을 대신하지 않는다 (design.md 9-6절).
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller {

    private final PaymentFacade paymentFacade;

    /** 알림 본문을 믿지 않고 거래를 직접 조회해 적용한다. 결과를 기다리거나 모르면 202, 결제가 끝났으면 200이다. */
    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<PaymentV1Dto.CallbackResponse>> callback(@RequestBody PaymentV1Dto.CallbackRequest request) {
        PaymentOutcome outcome = paymentFacade.handleCallback(request.transactionKey(), request.orderId());
        HttpStatus status = outcome.settled() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(PaymentV1Dto.CallbackResponse.from(outcome)));
    }
}
