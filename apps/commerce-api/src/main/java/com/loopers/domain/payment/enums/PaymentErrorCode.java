package com.loopers.domain.payment.enums;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    INVALID_PAYMENT_REQUEST("결제 요청 값이 올바르지 않습니다."),
    PAYMENT_ATTEMPT_CONFLICT("이미 다른 결제 시도가 있거나 같은 시도 키가 다른 주문에 쓰였습니다."),
    PAYMENT_NOT_FOUND("결제 시도가 없습니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
