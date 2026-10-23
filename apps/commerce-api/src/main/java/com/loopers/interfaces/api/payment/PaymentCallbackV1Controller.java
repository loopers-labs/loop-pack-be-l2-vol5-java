package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG가 호출하는 결과 알림 입구. 200은 “알림을 받아 처리했다”, 503은 “PG 조회로 확인하지 못했으니 다시 보내 달라”는 뜻이다.
 * 다시 보낼지는 PG가 정하며, 오지 않아도 고객 reconcile로 회복할 수 있다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments/callback")
public class PaymentCallbackV1Controller {

    private final OrderFacade orderFacade;

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> callback(@RequestBody PaymentCallbackV1Dto.CallbackRequest request) {
        if (orderFacade.handleCallback(request.providerOrderId()) == OrderFacade.CallbackResult.UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail(
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "PG 조회로 결제 결과를 확인하지 못했습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}
