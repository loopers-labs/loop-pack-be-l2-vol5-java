package com.loopers.domain.payment;

import com.loopers.domain.order.OrderConfirmedEvent;

/**
 * applyFinal 의 결과 (6주차 설계 D3·D4).
 *
 * @param applied           ⑥ 문지기가 1행이면 true. 결제 시도 기준이다
 * @param firstConfirmation 이 호출이 주문을 처음 CONFIRMED 로 만들었을 때만 채워지는 발행 payload. 아니면 null
 */
public record PaymentApplyResult(boolean applied, OrderConfirmedEvent firstConfirmation) {

    public static PaymentApplyResult notApplied() {
        return new PaymentApplyResult(false, null);
    }

    public static PaymentApplyResult applied(OrderConfirmedEvent firstConfirmation) {
        return new PaymentApplyResult(true, firstConfirmation);
    }

    public boolean isFirstConfirmation() {
        return firstConfirmation != null;
    }
}
