package com.loopers.interfaces.api.payment;

import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller implements PaymentV1ApiSpec {

    private final OrderPaymentFacade orderPaymentFacade;

    /** 인증 없는 호출자라 주문 정보는 돌려주지 않음 */
    @PostMapping("/callback")
    @Override
    public ApiResponse<Object> handleCallback(@RequestBody PaymentV1Dto.CallbackRequest request) {
        orderPaymentFacade.handleCallback(request.orderId());
        return ApiResponse.success();
    }
}
