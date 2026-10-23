package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.dto.PaymentCheckInfo;
import jakarta.validation.constraints.NotBlank;

public class PaymentDto {

    // PG 가 보내는 결과 알림. status 는 PG 가 전달하는 값이지만 결과를 정하는 데 쓰지 않는다. 알림은 확인을 요청하는 신호일
    // 뿐이고, 실제 결과는 transactionKey 로 PG 를 조회해서 확인한 사실만 반영한다.
    public record CallbackRequest(
        @NotBlank String providerOrderId,
        @NotBlank String transactionKey,
        @NotBlank String status
    ) {}

    // PG 에 돌려주는 수신 확인. 주문과 시도의 현재 상태이며, 조회하지 못해 저장된 상태를 그대로 돌려줬으면 lookupUnavailable 이
    // true 다(조회했다면 생략된다).
    public record CallbackResponse(Long orderId, String orderStatus, String paymentStatus, Boolean lookupUnavailable) {
        public static CallbackResponse from(PaymentCheckInfo result) {
            return new CallbackResponse(
                result.info().orderId(),
                result.info().orderStatus().name(),
                result.info().attemptStatus().name(),
                result.lookupFailed() ? Boolean.TRUE : null
            );
        }
    }
}
