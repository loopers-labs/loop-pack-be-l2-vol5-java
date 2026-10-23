package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * TX A 입력. "같은 요청" 비교에 쓰는 cardType 만 받고, 카드 번호는 TX A 에 들어가지 않는다 (설계 4-4).
 */
public record PaymentStartCommand(String attemptKey, String cardType) {

    public PaymentStartCommand {
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 필수입니다.");
        }
    }
}