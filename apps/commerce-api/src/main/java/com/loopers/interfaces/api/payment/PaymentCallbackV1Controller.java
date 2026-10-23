package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentCallbackFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentCallbackV1Controller implements PaymentCallbackV1ApiSpec {

    private final PaymentCallbackFacade paymentCallbackFacade;

    // 본문을 DTO로 받지 않는다 — 깨진 본문도 원문 그대로 남겨야 한다(docs/week5/design.md D-020).
    @PostMapping("/callback")
    @Override
    public ApiResponse<Object> receive(@RequestBody(required = false) String rawPayload) {
        paymentCallbackFacade.receive(rawPayload);
        return ApiResponse.success();
    }
}
