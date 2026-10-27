package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentCallbackV1Controller implements PaymentCallbackV1ApiSpec {

    private final OrderFacade orderFacade;

    @PostMapping("/callback")
    @Override
    public ApiResponse<Object> callback(@RequestBody PaymentCallbackV1Dto.CallbackRequest request) {
        PaymentGatewayResult result = request.toResult();
        if (result.outcome() == PaymentGatewayResult.Outcome.UNKNOWN) {
            log.warn("PG callback status 를 알 수 없음 [orderId = {}, status = {}]", request.orderId(), request.status());
        }
        orderFacade.applyPaymentCallback(request.orderId(), result);
        return ApiResponse.success();
    }
}
