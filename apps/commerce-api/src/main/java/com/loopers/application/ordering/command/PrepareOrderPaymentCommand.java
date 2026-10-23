package com.loopers.application.ordering.command;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentScenario;
import java.util.Objects;

/** 준비 단계에는 카드 원문이 필요하지 않다. 실효 조건과 저장된 주문 금액만 사용한다. */
public record PrepareOrderPaymentCommand(long userId, long orderId, String attemptKey, PaymentScenario scenario) {
    public PrepareOrderPaymentCommand {
        if (userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("사용자와 주문 ID는 양수여야 합니다.");
        }
        attemptKey = PaymentAttempt.normalizeKey(attemptKey);
        Objects.requireNonNull(scenario, "실효 결제 조건이 필요합니다.");
    }
}
