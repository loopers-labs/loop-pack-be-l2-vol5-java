package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentCallbackFacade;
import com.loopers.application.payment.PaymentCallbackResult;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 로컬 테스트 PG가 호출하는 결과 알림 수신 API다. 고객 인증(X-USER-ID)을 쓰지 않는다.
// PG는 10초 안의 200만 성공으로 보고, 일시 장애는 503으로 알려 재전송을 요청한다.
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller {
    private final PaymentCallbackFacade paymentCallbackFacade;

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Object>> callback(@RequestBody PaymentV1Dto.CallbackRequest request) {
        PaymentCallbackResult result = paymentCallbackFacade.handle(request.providerOrderId());
        if (result == PaymentCallbackResult.RETRY_LATER) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail(
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "결제 결과를 지금 확인할 수 없습니다. 알림을 다시 보내 주세요."
            ));
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}
