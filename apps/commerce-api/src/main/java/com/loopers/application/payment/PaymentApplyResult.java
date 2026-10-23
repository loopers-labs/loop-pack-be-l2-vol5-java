package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;

/** 결과 반영 TX 의 결과(설계 2.7). outcome 은 HTTP 코드가 아니며 호출자가 commit 뒤 응답으로 바꾼다. */
public record PaymentApplyResult(OrderInfo order, Outcome outcome) {

    public enum Outcome {
        FINAL,
        PENDING,
        UNAVAILABLE,
        CONFLICT
    }
}
