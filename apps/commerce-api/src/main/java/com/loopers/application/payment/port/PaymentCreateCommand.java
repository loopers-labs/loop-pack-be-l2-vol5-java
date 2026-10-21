package com.loopers.application.payment.port;

import com.loopers.domain.common.Money;

// 카드 번호는 toString·예외 메시지에 남기지 않는다(설계 21절).
public record PaymentCreateCommand(String providerOrderId, String cardType, String cardNo, Money amount) {
    private static final int MIN_ORDER_ID_LENGTH = 6;

    public PaymentCreateCommand {
        if (providerOrderId == null || providerOrderId.isBlank() || providerOrderId.length() < MIN_ORDER_ID_LENGTH) {
            throw new IllegalArgumentException("PG 주문 식별자는 공백이 아닌 6자 이상이어야 합니다.");
        }
        if (cardType == null || cardType.isBlank()) {
            throw new IllegalArgumentException("카드 종류가 필요합니다.");
        }
        if (cardNo == null || cardNo.isBlank()) {
            throw new IllegalArgumentException("카드 번호가 필요합니다.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("결제 금액이 필요합니다.");
        }
    }

    @Override
    public String toString() {
        return "PaymentCreateCommand[providerOrderId=" + providerOrderId + ", cardType=" + cardType
            + ", cardNo=****, amount=" + amount + "]";
    }
}
