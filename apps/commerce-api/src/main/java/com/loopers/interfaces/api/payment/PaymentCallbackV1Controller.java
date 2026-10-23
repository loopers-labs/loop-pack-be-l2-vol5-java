package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentCallbackV1Controller {

    private final PaymentFacade paymentFacade;

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Object>> callback(@RequestBody PaymentCallbackV1Dto.CallbackRequest request) {
        PaymentFacade.CallbackResult result = paymentFacade.onCallback(request.providerOrderId(), Instant.now());
        return switch (result) {
            case APPLIED -> ResponseEntity.ok(ApiResponse.success());
            case UNKNOWN_ORDER -> {
                log.warn("모르는 주문 번호의 callback: orderId={}", request.orderId());
                yield ResponseEntity.ok(ApiResponse.success());
            }
            case LOOKUP_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("PAYMENT_LOOKUP_UNAVAILABLE", "PG 조회를 할 수 없어 결과를 확인하지 못했습니다."));
        };
    }
}
