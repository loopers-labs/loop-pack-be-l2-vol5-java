package com.loopers.application.payment;

/** 고객 confirm 입력. 카드는 고정 합성 값만 허용하며 저장하지 않는다. */
public record PaymentConfirmCommand(String paymentAttemptKey, String cardType, String cardNo) {

    @Override
    public String toString() {
        return "PaymentConfirmCommand[paymentAttemptKey=" + paymentAttemptKey + "]";
    }
}
