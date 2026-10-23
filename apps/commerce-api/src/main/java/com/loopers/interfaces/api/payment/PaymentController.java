package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentResultCheckFacade;
import com.loopers.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 의 결과 알림을 받는다. PG 가 보내는 요청이라 고객 식별 대상이 아니다({@code SecurityConfig.IDENTIFIED_PATHS} 에
 * 등록하지 않는다). 그래서 알림 본문을 믿지 않고 PG 를 조회해 확인한다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController implements PaymentApiSpec {

    private final PaymentResultCheckFacade paymentResultCheckFacade;

    @PostMapping("/callback")
    @Override
    public ApiResponse<PaymentDto.CallbackResponse> callback(@Valid @RequestBody PaymentDto.CallbackRequest request) {
        return ApiResponse.success(PaymentDto.CallbackResponse.from(
            paymentResultCheckFacade.checkPaymentResultOnPgCallback(request.providerOrderId(), request.transactionKey())
        ));
    }
}
