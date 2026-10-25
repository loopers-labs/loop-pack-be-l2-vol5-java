package com.loopers.application.payment;

public record PaymentCreateCommand(String providerOrderId, long amount, String cardType, String cardNo) {

    // 카드 입력은 PG 생성 요청에만 전달하고 로그·예외 메시지에 남기지 않는다.
    @Override
    public String toString() {
        return "PaymentCreateCommand[providerOrderId=" + providerOrderId + ", amount=" + amount + ", card=redacted]";
    }
}
